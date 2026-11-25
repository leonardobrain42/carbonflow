-- D14 Q05.1: optional declared cumulative totals (start of crediting through the end of year y-1).
-- Additive. Declared and unitemized: the current reading remains the annual window and nothing is admitted.
ALTER TABLE app_open_dy_assessment
    ADD COLUMN cumulative_erc numeric(30,12),
    ADD COLUMN cumulative_erf numeric(30,12),
    ADD COLUMN cumulative_dy_preview numeric(30,12);

ALTER TABLE app_open_dy_assessment ADD CONSTRAINT ck_open_dy_cumulative CHECK (
    (cumulative_erc IS NULL AND cumulative_erf IS NULL AND cumulative_dy_preview IS NULL)
    OR (cumulative_erc IS NOT NULL AND cumulative_erf IS NOT NULL AND cumulative_dy_preview IS NOT NULL
        AND project_year>=2 AND outcome<>'INCONCLUSIVE'
        AND cumulative_erc>=erc AND cumulative_erf>=erf AND cumulative_erc>=0 AND cumulative_erf>=0));
