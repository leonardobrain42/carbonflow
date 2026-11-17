package br.carbonflow.pilot;

final class PilotProblem extends RuntimeException {
    final int status;
    final String code;
    final String field;
    final String rule;

    PilotProblem(int status, String code, String field, String rule, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
        this.rule = rule;
    }

    static PilotProblem invalid(String code, String field, String rule, String message) {
        return new PilotProblem(422, code, field, rule, message);
    }
}
