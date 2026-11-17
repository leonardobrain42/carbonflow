-- Entrada controlada para medir o cálculo e o fechamento com volumes altos.
-- Os lotes cronometrados passam pela API normal e pela fila, separadamente.
BEGIN;
SET LOCAL TIME ZONE 'UTC';

CREATE TEMP TABLE envelope_facts ON COMMIT DROP AS
WITH volume(project,n) AS (VALUES ('PIL-SYN-BR-001',50000),('SIM-PROJETO-INTERNO',47500)),
windows AS (
    SELECT v.project,k.kind,c.channel, w.start_at::timestamptz, w.end_at::timestamptz,
           CASE
             WHEN current_setting('carbonflow.envelope_profile',true)='hourly' AND k.kind='EC'
               THEN (extract(epoch FROM w.end_at::timestamptz-w.start_at::timestamptz)/3600)::integer
             WHEN current_setting('carbonflow.envelope_profile',true)='hourly' AND w.cut='2027'
               THEN v.n-8784+CASE c.channel WHEN 1 THEN 2 ELSE 0 END
             WHEN w.cut='2027' THEN 1
             WHEN w.cut='APRIL' THEN 18000
             ELSE v.n-18000-CASE c.channel WHEN 1 THEN 2 ELSE 0 END
           END AS parts
    FROM volume v CROSS JOIN (VALUES ('ED'),('EC')) k(kind) CROSS JOIN (VALUES (1),(2)) c(channel)
    CROSS JOIN (VALUES
        ('2027','2027-10-01T03:00:00Z','2028-01-01T03:00:00Z'),
        ('APRIL','2028-01-01T03:00:00Z','2028-04-01T03:00:00Z'),
        ('END','2028-04-01T03:00:00Z','2028-10-01T03:00:00Z')) w(cut,start_at,end_at)
), intervals AS (
    SELECT project,kind,channel,
           start_at+((extract(epoch FROM end_at-start_at)::bigint*(slot-1))/parts)*interval '1 second' AS from_at,
           start_at+((extract(epoch FROM end_at-start_at)::bigint*slot)/parts)*interval '1 second' AS to_at,
           CASE WHEN kind='ED' THEN 'SIM-ED-0'||channel WHEN channel=2 THEN 'SIM-MED-02'
                WHEN start_at<'2028-04-01T03:00:00Z' THEN 'SIM-MED-01' ELSE 'SIM-MED-03' END AS meter,
           CASE WHEN kind='ED' THEN '0.1' WHEN current_setting('carbonflow.envelope_profile',true)='hourly'
                THEN '2' ELSE '0.12' END AS amount
    FROM windows CROSS JOIN LATERAL generate_series(1,parts) slot
), numbered AS (
    SELECT *, row_number() OVER (PARTITION BY project,kind ORDER BY channel,from_at)::integer AS position
    FROM intervals
), named AS (
    SELECT *, CASE kind WHEN 'ED' THEN 'SIM-DCFC-0'||channel ELSE 'SIM-EC-0'||channel END AS contour,
           CASE WHEN kind='ED' THEN 'SIM-BEV-0'||channel END AS vehicle,
           'SIM-E812-'||kind||'-'||position AS ref,
           to_char(from_at,'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS from_text,
           to_char(to_at,'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS to_text
    FROM numbered
)
SELECT project,kind,contour,vehicle,ref,from_at,to_at,(position-1)/10000 AS batch,
       (position-1)%10000+2 AS line,gen_random_uuid() AS row_id,gen_random_uuid() AS observation_id,
       concat_ws(',',kind,ref,from_text,to_text,'America/Sao_Paulo','SIM-PATIO-01',
                 CASE kind WHEN 'ED' THEN contour ELSE '' END,coalesce(vehicle,''),
                 CASE kind WHEN 'EC' THEN contour ELSE '' END,meter,amount,'kWh') AS original,
       jsonb_build_object('kind',kind,'sourceFactId',ref,'site','SIM-PATIO-01',
           'charger',CASE kind WHEN 'ED' THEN contour END,'vehicle',vehicle,
           'circuit',CASE kind WHEN 'EC' THEN contour END,'meter',meter,
           'sourceStart',from_text,'sourceEndExclusive',to_text,'zone','America/Sao_Paulo',
           'start',from_text,'endExclusive',to_text,'quantity',amount,'unit','kWh','kwh',amount) AS fact,
       encode(sha256(convert_to(concat_ws('|',kind,contour,coalesce(vehicle,''),meter,from_text,to_text,amount),'UTF8')),'hex') AS hash
FROM named;

CREATE TEMP TABLE envelope_batches ON COMMIT DROP AS
SELECT project,kind,batch,count(*) AS rows,gen_random_uuid() AS file,gen_random_uuid() AS job,gen_random_uuid() AS event,
       convert_to('kind,sourceFactId,start,endExclusive,zone,siteId,chargerId,vehicleId,circuitId,meterId,quantity,unit'
                  ||chr(10)||string_agg(original,chr(10) ORDER BY line)||chr(10),'UTF8') AS content
FROM envelope_facts GROUP BY project,kind,batch;

INSERT INTO pilot_access_event (id,project_id,actor_id,action,outcome,target,occurred_at,details)
SELECT event,project,'SIM-REV','FIXTURE_VOLUME_E8_012','PERMITIDO',job::text,clock_timestamp(),
       jsonb_build_object('kit','0.2.14','source','scripts/envelope/seed.sql','kind',kind,'batch',batch,'rows',rows)
FROM envelope_batches;

INSERT INTO pilot_import_file (project_id,id,sha256,original_name,source_id,authorization_id,received_by,bytes)
SELECT project,file,encode(sha256(content),'hex'),'volume-'||kind||'-'||batch||'.csv',
       'SIM-OP-01','SIM-AUT-ED-EC','SIM-OP',content FROM envelope_batches;

INSERT INTO pilot_import_job (project_id,id,file_id,mapping_id,mapping_version,period,configuration_version,requested_by,state,total,processed,columns)
SELECT project,job,file,'SIM-CSV-UTF8-ED-EC',1,'M1',1,'SIM-OP','CONCLUIDO',rows,rows,12 FROM envelope_batches;

INSERT INTO pilot_import_row (project_id,id,job_id,line,original,classification,fact,source_fact,physical_hash,
                              kind,channel,vehicle,start_at,end_at,review_version,decision,proposal,resolution)
SELECT f.project,f.row_id,b.job,f.line,f.original,'CANDIDATA',f.fact,f.ref,f.hash,f.kind,f.contour,f.vehicle,f.from_at,f.to_at,3,'ADMITIDA',
       jsonb_build_object('preparedBy','SIM-PREP','reason','Pré-carga sintética de referência E8-012'),
       jsonb_build_object('reviewedBy','SIM-REV','reason','Pré-carga sintética de referência E8-012',
                         'observation',jsonb_build_object('id',f.observation_id,'version',1),'eventId',b.event)
FROM envelope_facts f JOIN envelope_batches b USING(project,kind,batch);

INSERT INTO pilot_observation_revision(project_id,id,version,row_id,period,configuration_version,kind,local_year,channel,vehicle,
                                       start_at,end_at,physical_hash,fact,resolution,event_id)
SELECT f.project,f.observation_id,1,f.row_id,'M1',1,f.kind,extract(year FROM f.from_at AT TIME ZONE 'America/Sao_Paulo'),
       f.contour,f.vehicle,f.from_at,f.to_at,f.hash,f.fact,r.resolution,b.event
FROM envelope_facts f JOIN envelope_batches b USING(project,kind,batch)
JOIN pilot_import_row r ON (r.project_id,r.id)=(f.project,f.row_id);

INSERT INTO pilot_observation_head SELECT project,observation_id,1 FROM envelope_facts;
INSERT INTO pilot_observation_alias SELECT project,ref,observation_id FROM envelope_facts;
COMMIT;

ANALYZE pilot_import_row;
ANALYZE pilot_import_job;
ANALYZE pilot_observation_revision;
ANALYZE pilot_observation_head;
ANALYZE pilot_observation_alias;
