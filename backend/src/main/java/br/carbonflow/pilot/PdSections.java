package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class PdSections {
    record Input(int guideVersion, String narrative) {}
    record Version(int number, int guideVersion, UUID author, String narrative, String sha256) {}
    record Section(ProjectGuides.Guide guide, Version latest,
                   OpenMonitoringDossiers.PdDependency monitoringDependency,
                   OpenPdCalculationNote.Note calculation,
                   OpenPdAdditionalityNote.Note additionality,
                   OpenPdApplicabilityNote.Note applicability,
                   OpenPdBaselineNote.Note baseline) {}
    record Artifact(String text, String sha256) {}
    private final DynamicProjectScope scopes;
    private final ProjectGuides guides;
    private final OpenE8ProjectRuns runs;
    private final OpenApplicability applicability;
    private final ObjectMapper mapper;

    PdSections(DynamicProjectScope scopes, ProjectGuides guides, OpenE8ProjectRuns runs,
               OpenApplicability applicability, ObjectMapper mapper) {
        this.scopes = scopes;
        this.guides = guides;
        this.runs = runs;
        this.applicability = applicability;
        this.mapper = mapper;
    }

    List<Section> list(String token, String project) {
        return list(token, project, null, null);
    }

    List<Section> list(String token, String project, String period, UUID runId) {
        return scopes.read(token, project, "VIEW_DOSSIER", (c, scope) -> {
            boolean selected = period != null || runId != null;
            if (selected && !scope.actions().contains("VIEW_OPEN_E8_RUN")) throw missing();
            if (selected && (period == null || runId == null))
                throw new PilotProblem(400,"REFERENCIA_INCOMPLETA","run","CF-TRUST-001",
                    "Informe período e tentativa juntos.");
            var sections = new ArrayList<Section>();
            var run = selected ? OpenE8ProjectRuns.find(c, project, period, runId)
                : scope.actions().contains("VIEW_OPEN_E8_RUN") ? runs.newest(c, project) : null;
            OpenPdCalculationNote.Note calculation = scope.actions().contains("VIEW_OPEN_E8_RUN")
                ? OpenPdCalculationNote.from(run, OpenPdCalculationProvenance.read(c, scope, run))
                : OpenPdCalculationNote.restricted();
            OpenApplicability.Readiness readiness = applicability.readinessOf(c, project);
            OpenE8FleetSelections.View fleet = OpenE8FleetSelections.newest(c, project);
            // BL-EX-03: the exact EX ANTE reference (newest version) named in the baseline section, for those who
            // may read the project's fleet context. Without that action the field stays null (no inference).
            var exAnte = scope.actions().contains(OpenBaselineFleetContext.ACTION)
                ? OpenBaselineFleetSelections.history(c, project).stream().findFirst().orElse(null) : null;
            for (var guide : guides.catalog(c, scope).guides()) {
                Version current=latest(c,project,guide.id());
                sections.add(new Section(guide,current,
                    current==null?null:OpenMonitoringDossiers.pdDependency(
                        c,project,guide.id(),current.number()),
                    "quantification".equals(guide.id()) ? calculation : null,
                    "additionality".equals(guide.id())
                        ? OpenPdAdditionalityNote.from(readiness, guide.answers()) : null,
                    "applicability".equals(guide.id())
                        ? OpenPdApplicabilityNote.from(readiness) : null,
                    "baseline".equals(guide.id())
                        ? OpenPdBaselineNote.from(fleet, exAnte, guide.answers()) : null));
            }
            return sections;
        });
    }

    ProjectGuides.Guide visible(Connection c, DynamicProjectScope.Context scope, String id) throws SQLException {
        return guides.catalog(c, scope).guides().stream().filter(g -> g.id().equals(id))
                .findFirst().orElseThrow(PdSections::missing);
    }

    Version save(String token, String project, String id, String match, Input input) {
        return scopes.write(token, project, "PREPARE_DOSSIER", (c,scope) -> {
            var catalog = guides.catalog(c, scope);
            var guide = catalog.guides().stream().filter(g -> g.id().equals(id)).findFirst()
                    .orElseThrow(PdSections::missing);
            // A04: template and edition come from the package of the project's methodology, not from a constant.
            var pd = ProjectJourney.profileById(catalog.methodology()).pd();
            if (input == null || input.narrative() == null || input.narrative().isBlank()
                    || input.narrative().length() > 12000) {
                throw new PilotProblem(400,"TEXTO_INVALIDO","narrative","CF-IMP-023","Informe uma narrativa de até 12.000 caracteres.");
            }
            int expected = PilotCommands.expected(match);
            var current = latest(c,project,id);
            String currentMrManifest = OpenMonitoringDossiers.manifest(c,project).sha256();
            String pinnedMrManifest = OpenMonitoringDossiers.pinnedPd(c,project,id,
                current==null?0:current.number());
            boolean sameMonitoringDependencies = !java.util.Set.of("monitoring","quantification").contains(id)
                || java.util.Objects.equals(currentMrManifest,pinnedMrManifest);
            String text = input.narrative().strip();
            if (current != null && expected == current.number()-1 && current.author().equals(scope.actor().id())
                    && input.guideVersion() == current.guideVersion() && text.equals(current.narrative())
                    && sameMonitoringDependencies) return current;
            PilotCommands.requireVersion(expected,current == null ? 0 : current.number());
            if (guide.version() == 0 || guide.version() != input.guideVersion()) {
                throw conflict("Consulte a revisão atual do guia antes de redigir.");
            }
            if (current != null && current.guideVersion() == guide.version() &&
                text.equals(current.narrative()) && sameMonitoringDependencies) return current;
            if (current != null && PdReviews.locked(c,project,id,current.number())) {
                throw conflict("Reabra a seção antes de criar outra versão.");
            }
            int version = expected+1;
            var artifact = mapper.createObjectNode();
            artifact.put("kind","PD_SECTION_DRAFT").put("exposure","RESERVED").put("projectId",project)
                    .put("sectionId",id).put("version",version).put("authorId",scope.actor().id().toString())
                    .put("createdAt",OffsetDateTime.now(java.time.ZoneOffset.UTC).toString())
                    .put("template",pd.template()).put("edition",pd.templateEdition())
                    .put("guideVersion",guide.version()).put("narrative",text);
            if (java.util.Set.of("monitoring","quantification").contains(id) && currentMrManifest!=null)
                artifact.put("mrDependencyManifestSha256",currentMrManifest);
            artifact.set("fields",mapper.valueToTree(guide.fields()));
            artifact.set("inputs",mapper.valueToTree(new java.util.TreeMap<>(guide.answers())));
            artifact.set("missing",mapper.valueToTree(guide.missing()));
            String payload = artifact.toString();
            String digest = hash(payload);
            update(c,"""
                    INSERT INTO app_pd_section_version(project_id,section_id,version,guide_version,author_id,narrative,artifact,sha256)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,project,id,version,guide.version(),scope.actor().id(),text,payload,digest);
            OpenMonitoringDossiers.pinPd(c,project,id,version);
            return new Version(version,guide.version(),scope.actor().id(),text,digest);
        });
    }

    Artifact download(String token,String project,String id,int version) {
        return scopes.read(token,project,"DOWNLOAD_DOSSIER",(c,scope) -> {
            visible(c,scope,id);
            Artifact artifact = first(c,"SELECT artifact,sha256 FROM app_pd_section_version WHERE project_id=? AND section_id=? AND version=?",
                    r -> new Artifact(r.getString(1),r.getString(2)), project,id,version);
            if (artifact == null) throw missing();
            return artifact;
        });
    }

    static Version latest(Connection c,String project,String id) throws SQLException {
        return first(c,"""
                SELECT version,guide_version,author_id,narrative,sha256 FROM app_pd_section_version
                WHERE project_id=? AND section_id=? ORDER BY version DESC LIMIT 1
                """, r -> new Version(r.getInt(1),r.getInt(2),r.getObject(3,UUID.class),r.getString(4),r.getString(5)),project,id);
    }

    static PilotProblem conflict(String message) {
        return new PilotProblem(409,"SECAO_INDISPONIVEL","section","CF-IMP-023",message);
    }

    static PilotProblem missing() {
        return new PilotProblem(404,"SECAO_INDISPONIVEL","section","CF-IMP-023","Seção indisponível.");
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
