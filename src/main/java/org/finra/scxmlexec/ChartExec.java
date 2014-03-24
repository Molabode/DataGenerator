package org.finra.scxmlexec;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.scxml.SCXMLExecutor;
import org.apache.commons.scxml.io.SCXMLParser;
import org.apache.commons.scxml.model.SCXML;
import org.apache.hadoop.io.SequenceFile;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

public class ChartExec {

    protected static final Logger log = Logger.getLogger(ChartExec.class);

    private static boolean isDebugEnabled = false;

    /**
     * A comma separated list of variables to be passed to the OutputFormatter
     */
    private String outputVariables;

    /**
     * The input SCXML chart file
     */
    private String inputFileName = null;

    /**
     * The set of initial variables that the user wants to set
     */
    private String initialVariables = null;

    private static HashSet<String> varsOut = null;
    /**
     * The initial set of events to trigger before re-searching for a new
     * scenario
     */
    private String initialEvents = null;

    private static final ArrayList<String> initialEventsList = new ArrayList<String>();

    /**
     * Length of scenario
     */
    private int lengthOfScenario = 5;

    /**
     * Generate -ve scenarios
     */
    private boolean generateNegativeScenarios = false;

    /**
     * Initial variables map
     */
    private static final HashMap<String, String> initialVariablesMap = new HashMap<String, String>();

    private int maxEventReps = 1;

    private int maxScenarios = 10000;

    private OutputStream os = null;

    private SequenceFile.Writer sequenceFileWriter = null;

    private DataConsumer userDataOutput = new DefaultOutput(System.out);

    private int bootstrapMin = 0;

    private SearchDistributor userSearchDistributor = new DefaultDistributor();

    private Map<String, String> userSearchDistributorOptions = new HashMap<String, String>();

    private int threadCount = 1;

    public ChartExec() {
        isDebugEnabled = false;
    }

    public void setUserSearchDistributor(SearchDistributor userSearchDistributor) {
        this.userSearchDistributor = userSearchDistributor;
    }

    public void setBootstrapMin(int depth) {
        this.bootstrapMin = depth;
    }

    public void setUserDataOutput(DataConsumer userDataOutput) {
        this.userDataOutput = userDataOutput;
    }

    public String getOutputVariables() {
        return outputVariables;
    }

    public void setOutputVariables(String outputVariables) {
        this.outputVariables = outputVariables;
    }

    public String getInitialEvents() {
        return initialEvents;
    }

    public void setInitialEvents(String initialEvents) {
        this.initialEvents = initialEvents;
    }

    public String getInitialVariables() {
        return initialVariables;
    }

    public void setInitialVariables(String initialVariables) {
        this.initialVariables = initialVariables;
    }

    public OutputStream getOs() {
        return os;
    }

    public void setOs(OutputStream os) {
        this.os = os;
    }

    public String getInputFileName() {
        return inputFileName;
    }

    public SequenceFile.Writer getSequenceFileWriter() {
        return sequenceFileWriter;
    }

    public void setSequenceFileWriter(SequenceFile.Writer sequenceFileWriter) {
        this.sequenceFileWriter = sequenceFileWriter;
    }

    public void setInputFileName(String inputFileName) {
        this.inputFileName = inputFileName;
    }

    public boolean isGenerateNegativeScenarios() {
        return generateNegativeScenarios;
    }

    public void setGenerateNegativeScenarios(boolean generateNegativeScenarios) {
        this.generateNegativeScenarios = generateNegativeScenarios;
    }

    public int getLengthOfScenario() {
        return lengthOfScenario;
    }

    public void setLengthOfScenario(int lengthOfScenario) {
        this.lengthOfScenario = lengthOfScenario;
    }

    public int getMaxEventReps() {
        return maxEventReps;
    }

    public void setMaxEventReps(int maxEventReps) {
        this.maxEventReps = maxEventReps;
    }

    public int getMaxScenarios() {
        return maxScenarios;
    }

    public void setMaxScenarios(int maxScenarios) {
        this.maxScenarios = maxScenarios;
    }

    private boolean doSanityChecks() throws IOException {
        /*        if (outputVariables == null) {
         throw new IOException("Cannot continuw with outputVariables=null");
         }*/

        if (inputFileName == null) {
            throw new IOException("Error:, input file cannot be null");
        }

        if (!(new File(inputFileName)).exists()) {
            throw new IOException("Error:, input file does not exist");
        }

        // Parse the initial events
        if (initialEvents != null) {
            initialEventsList.addAll(Arrays.asList(StringUtils.split(initialEvents, ",")));
        }

        // Parse the initial variables
        if (initialVariables != null) {
            String[] vars = StringUtils.split(initialVariables, ",");
            for (String var : vars) {
                if (var.contains("=")) {
                    String[] assignment = var.split("=");
                    if (assignment[0] == null || assignment[1] == null || assignment[0].length() == 0
                            || assignment[1].length() == 0) {
                        throw new IOException("Error while processing initial variable assignment for: " + var);
                    }
                    initialVariablesMap.put(assignment[0], assignment[1]);
                } else {
                    throw new IOException("Error while processing initial variable assignment for: " + var);
                }
            }
        }

        return true;
    }

    private HashSet<String> extractOutputVariables(String filePathName) throws IOException {
        log.info("Extracting variables from file: " + filePathName);
        List<String> linesRead = FileUtils.readLines(new File(filePathName));
        HashSet<String> outputVars = new HashSet<String>();
        for (String line : linesRead) {
            if (line.contains("var_out")) {
                int startIndex = line.indexOf("var_out");
                int lastIndex = startIndex;
                while (lastIndex < line.length() && (Character.isLetter(line.charAt(lastIndex))
                        || Character.isDigit(line.charAt(lastIndex))
                        || line.charAt(lastIndex) == '_'
                        || line.charAt(lastIndex) == '-')) {
                    lastIndex++;
                }
                if (lastIndex == line.length()) {
                    throw new IOException("Reached the end of the line while parsing variable name in line: '" + line
                            + "'.");
                }
                String varName = line.substring(startIndex, lastIndex);
                log.info("Found variable: " + varName);
                outputVars.add(varName);
            }
        }

        return outputVars;
    }


    private void initializeData() throws Exception {
        doSanityChecks();
        // Load the state machine
        String absolutePath = (new File(inputFileName)).getAbsolutePath();
        log.info("Processing file:" + absolutePath);
        varsOut = extractOutputVariables(absolutePath);
    }

    public void setDistributorOption(String key, String value) {
        userSearchDistributorOptions.put(key, value);
    }

    public List<SearchProblem> prepare() throws Exception {
        initializeData();
        DataGeneratorExecutor executor = new DataGeneratorExecutor(inputFileName);

        // Get BFS-generated states for bootstrapping parallel search
        List<PossibleState> bfsStates = executor.searchForScenarios(varsOut, initialVariablesMap, initialEventsList,
                maxEventReps, maxScenarios, lengthOfScenario, bootstrapMin);

        List<SearchProblem> dfsProblems = new ArrayList<SearchProblem>();

        for (PossibleState state : bfsStates) {
            dfsProblems.add(new SearchProblem(state, varsOut, initialVariablesMap, initialEventsList));
        }

        return dfsProblems;
    }

    public void process() throws Exception {
        SCXML stateMachine = SCXMLParser.parse((new File(inputFileName)).toURI().toURL(), null);

        List<SearchProblem> dfsProblems = prepare();
        log.info("Found " + dfsProblems.size() + " states to distribute");

        userSearchDistributor.setOptions(userSearchDistributorOptions);
        userSearchDistributor.setStateMachineText(FileUtils.readFileToString(new File(inputFileName)));
        userSearchDistributor.setDataConsumer(userDataOutput);
        userSearchDistributor.distribute(dfsProblems);

        log.info("DONE.");
    }

    private static HashMap<String, String> readVarsOut(SCXMLExecutor executor) {
        HashMap<String, String> result = new HashMap<String, String>();
        for (String varName : varsOut) {
            result.put(varName, (String) executor.getRootContext().get(varName));
        }
        return result;
    }

}

