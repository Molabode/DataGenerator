package org.finra.scxmlexec;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.scxml.SCXMLExecutor;
import org.apache.commons.scxml.SCXMLExpressionException;
import org.apache.commons.scxml.TriggerEvent;
import org.apache.commons.scxml.env.jsp.ELContext;
import org.apache.commons.scxml.env.jsp.ELEvaluator;
import org.apache.commons.scxml.io.SCXMLParser;
import org.apache.commons.scxml.model.ModelException;
import org.apache.commons.scxml.model.SCXML;
import org.apache.commons.scxml.model.Transition;
import org.apache.commons.scxml.model.TransitionTarget;
import org.apache.hadoop.io.SequenceFile;
import org.apache.log4j.Logger;
import org.xml.sax.SAXException;

public class ChartExec implements Closeable {

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

    private final ConcurrentLinkedQueue<HashMap<String, String>> queue = new ConcurrentLinkedQueue<HashMap<String, String>>();

    private final Thread outputThread;

    private DataConsumer userDataOutput = new DefaultOutput(System.out);

    public ChartExec() {
        isDebugEnabled = false;

        outputThread = new Thread() {
            @Override
            public void run() {
                try {
                    produceOutput();
                } catch (IOException ex) {
                    log.error("Error during output", ex);
                }
            }
        };
        outputThread.start();
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
                    throw new IOException("Reached the end of the line while parsing variable name in line: '" + line + "'.");
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

    public void process() throws Exception {
        initializeData();
        DataGeneratorExecutor executor = new DataGeneratorExecutor(inputFileName);
        executor.searchForScenariosDFS(queue, varsOut, initialVariablesMap, initialEventsList);
    }

    public void processWithHybrid() throws Exception {
        initializeData();
        DataGeneratorExecutor executor = new DataGeneratorExecutor(inputFileName);
        executor.searchForScenarios(varsOut, initialVariablesMap, initialEventsList, maxEventReps, maxScenarios, lengthOfScenario, 3);
    }

    @Override
    public void close() throws IOException {
        outputThread.interrupt();
    }

    private static HashMap<String, String> readVarsOut(SCXMLExecutor executor) {
        HashMap<String, String> result = new HashMap<String, String>();
        for (String varName : varsOut) {
            result.put(varName, (String) executor.getRootContext().get(varName));
        }
        return result;
    }

    public void produceOutput_test(SCXMLExecutor executor) {
        System.out.println("***" + executor.getRootContext().get("var_out_RECORD_TYPE") + " "
                + executor.getRootContext().get("var_out_REQUEST_IDENTIFIER") + " "
                + executor.getRootContext().get("var_out_MANIFEST_GENERATION_DATETIME"));
    }

    private void produceOutput() throws IOException {
        while (!Thread.interrupted()) {
            while (!Thread.interrupted() && queue.isEmpty()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                }
            }

            if (!Thread.interrupted()) {
                HashMap<String, String> row = queue.poll();

                userDataOutput.consume(row);
            }
        }
    }
}

