package extproc;

public class ProcessingOptions {
    public Boolean logStream;
    public Boolean logPhases;
    public Boolean updateExtProcHeader;
    public Boolean updateDurationHeader;

    // TODO: properties file
    public ProcessingOptions() {
        logStream = false;
        logPhases = true;
        updateExtProcHeader = false;
        updateDurationHeader = false;
    }
}
