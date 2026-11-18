package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class PdReviews {
    enum State { DRAFT, SUBMITTED, RETURNED, APPROVED_INTERNAL, FROZEN_INTERNAL, REOPENED }
    record Event(int seq, State state, String reason, UUID actor, String actorName, OffsetDateTime at, Integer competenceVersion) {}
    record Item(String id,String title, PdSections.Version section, boolean outdated, boolean ownerOnly, String authorName, List<Event> history, List<State> actions) {}
    record Decision(State state,String reason) {}
    private final DynamicProjectScope scopes;
    private final PdSections sections;
    private final ProjectGuides guides;
    PdReviews(DynamicProjectScope scopes,PdSections sections,ProjectGuides guides) {
        this.scopes=scopes; this.sections=sections; this.guides=guides;
    }

    List<Item> queue(String token,String project) {
        return scopes.read(token,project,"VIEW_PD_REVIEW",(c,scope) -> {
            var items = new ArrayList<Item>();
            for (var guide : guides.catalog(c,scope).guides()) {
                var section = PdSections.latest(c,project,guide.id());
                if (section != null) items.add(item(c,scope,guide,section));
            }
            return items;
        });
    }

    List<Event> history(String token,String project,String id,int version) {
        return scopes.read(token,project,"VIEW_PD_REVIEW",(c,scope) -> {
            sections.visible(c,scope,id);
            var latest = PdSections.latest(c,project,id);
            if (latest == null || version < 1 || version > latest.number()) throw PdSections.missing();
            return events(c,project,id,version);
        });
    }

    Item decide(String token,String project,String id,int version,String match,Decision input) {
        if (input == null || input.state()==null || input.reason()==null || input.reason().isBlank() || input.reason().length()>2000)
            throw new PilotProblem(400,"PARECER_INVALIDO","reason","CF-IMP-024","Informe um motivo de até 2.000 caracteres.");
        boolean editorial = input.state()==State.SUBMITTED || input.state()==State.REOPENED;
        return scopes.write(token,project,editorial ? "PREPARE_DOSSIER" : "DECIDE_PD_REVIEW",(c,scope) -> {
            var guide = sections.visible(c,scope,id);
            var latest = PdSections.latest(c,project,id);
            if (latest==null || latest.number()!=version) throw PdSections.conflict("Consulte a versão atual da seção.");
            var view = item(c,scope,guide,latest);
            int expected = PilotCommands.expected(match);
            var events = view.history();
            if (!events.isEmpty() && expected==events.size()-1) {
                var last=events.getLast();
                if (last.actor().equals(scope.actor().id()) && last.state()==input.state() && last.reason().equals(input.reason().strip())) return view;
            }
            PilotCommands.requireVersion(expected,events.size());
            if (!view.actions().contains(input.state())) throw PdSections.conflict("Ação indisponível para esta autoria, revisão ou competência.");
            Integer competence = input.state()==State.FROZEN_INTERNAL ? competence(c,scope,id) : null;
            if (input.state()==State.FROZEN_INTERNAL && competence==null) throw PdSections.conflict("A competência aprovada expirou.");
            update(c,"""
                    INSERT INTO app_pd_review_event(project_id,section_id,version,seq,state,reason,actor_id,competence_version)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,project,id,version,expected+1,input.state().name(),input.reason().strip(),scope.actor().id(),competence);
            return item(c,scope,guide,latest);
        });
    }

    private Item item(Connection c,DynamicProjectScope.Context scope,ProjectGuides.Guide guide,PdSections.Version section) throws SQLException {
        var history=events(c,scope.id(),guide.id(),section.number());
        State state=history.isEmpty()?State.DRAFT:history.getLast().state();
        boolean outdated=guide.version()!=section.guideVersion();
        List<State> actions=new ArrayList<>();
        boolean ownerOnly = Boolean.TRUE.equals(first(c, "SELECT activity='OWNER' FROM app_technical_guide WHERE id=?",
                r -> r.getBoolean(1),guide.id()));
        if (scope.actions().contains("PREPARE_DOSSIER")) {
            if (!ownerOnly && !outdated && List.of(State.DRAFT,State.REOPENED,State.RETURNED).contains(state)) actions.add(State.SUBMITTED);
            if (List.of(State.SUBMITTED,State.APPROVED_INTERNAL,State.FROZEN_INTERNAL).contains(state)) actions.add(State.REOPENED);
        }
        if (scope.actions().contains("DECIDE_PD_REVIEW") && !section.author().equals(scope.actor().id())) {
            UUID sourceAuthor=first(c,"SELECT actor_id FROM app_technical_guide_revision WHERE project_id=? AND guide_id=? AND version=?",
                    r->r.getObject(1,UUID.class),scope.id(),guide.id(),section.guideVersion());
            boolean submitter = history.stream().anyMatch(e -> e.state() == State.SUBMITTED && e.actor().equals(scope.actor().id()));
            if (!submitter && !scope.actor().id().equals(sourceAuthor)) {
                if (state==State.SUBMITTED) {
                    actions.add(State.RETURNED);
                    if (!outdated) actions.add(State.APPROVED_INTERNAL);
                }
                if (!outdated && state==State.APPROVED_INTERNAL && competence(c,scope,guide.id())!=null) actions.add(State.FROZEN_INTERNAL);
            }
        }
        String authorName = first(c,"SELECT display_name FROM app_identity WHERE id=?",r -> r.getString(1),section.author());
        return new Item(guide.id(),guide.title(),section,outdated,ownerOnly,authorName,history,actions);
    }

    private static Integer competence(Connection c,DynamicProjectScope.Context scope,String id) throws SQLException {
        return first(c,"""
                SELECT CASE WHEN valid_until>statement_timestamp() THEN version END FROM app_pd_reviewer_competence
                WHERE project_id=? AND section_id=? AND reviewer_id=? ORDER BY version DESC LIMIT 1
                """,r->r.getObject(1,Integer.class),scope.id(),id,scope.actor().id());
    }

    static boolean locked(Connection c,String project,String id,int version) throws SQLException {
        var history=events(c,project,id,version);
        return !history.isEmpty() && List.of(State.SUBMITTED,State.APPROVED_INTERNAL,State.FROZEN_INTERNAL).contains(history.getLast().state());
    }

    private static List<Event> events(Connection c,String project,String id,int version) throws SQLException {
        return query(c,"""
                SELECT seq,state,reason,actor_id,decided_at,competence_version,i.display_name FROM app_pd_review_event e
                JOIN app_identity i ON i.id=e.actor_id
                WHERE e.project_id=? AND e.section_id=? AND e.version=? ORDER BY e.seq
                """,r->new Event(r.getInt(1),State.valueOf(r.getString(2)),r.getString(3),r.getObject(4,UUID.class),r.getString(7),
                        r.getObject(5,OffsetDateTime.class),r.getObject(6,Integer.class)),project,id,version);
    }
}
