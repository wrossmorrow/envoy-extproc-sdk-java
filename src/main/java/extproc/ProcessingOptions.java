package extproc;

public class ProcessingOptions {
    public Boolean logStream;
    public Boolean logPhases;
    public Boolean updateExtProcHeader;
    public Boolean updateDurationHeader;

    public ProcessingOptions() {
        logStream = Boolean.parseBoolean(System.getProperty("extproc.log.stream", "false"));
        logPhases = Boolean.parseBoolean(System.getProperty("extproc.log.phases", "false"));
        updateExtProcHeader = Boolean.parseBoolean(System.getProperty("extproc.headers.processors", "false"));
        updateDurationHeader = Boolean.parseBoolean(System.getProperty("extproc.headers.duration", "false"));
    }
}
