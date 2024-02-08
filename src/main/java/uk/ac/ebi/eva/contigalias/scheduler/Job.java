package uk.ac.ebi.eva.contigalias.scheduler;

public class Job {
    private final JobType type;
    private final String parameter;

    public Job(JobType type, String parameter) {
        this.type = type;
        this.parameter = parameter;
    }

    public JobType getType() {
        return type;
    }

    public String getParameter() {
        return parameter;
    }
}