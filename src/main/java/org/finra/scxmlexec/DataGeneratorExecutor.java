package org.finra.scxmlexec;

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
import org.apache.log4j.Logger;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.*;

/**
 * Created by robbinbr on 3/3/14.
 */
public class DataGeneratorExecutor extends SCXMLExecutor {

    /**
     * Defines a possible state that a state can be in. A possible state is a
     * combination of a state and values for variables.
     */
    static class PossibleState {

        String id;
        /**
         * The name of the next state
         */
        String nextStateName;
        String transitionEvent;

        boolean varsInspected = false;

        /**
         * The variables that need to be set before jumping to that state
         */
        final HashMap<String, String> variablesAssignment = new HashMap<String, String>();

        @Override
        public String toString() {
            return "id=" + id + ",next:" + nextStateName + ",trans:" + transitionEvent + ",varsInspected:" + varsInspected + ",vars:" + variablesAssignment;
        }
    }


    protected static final Logger log = Logger.getLogger(DataGeneratorExecutor.class);

    private StateMachineListener listener = new StateMachineListener();

    public DataGeneratorExecutor(){
        super();
    }

    public DataGeneratorExecutor(String filePath) throws IOException, ModelException, SAXException {
        String absolutePath = (new File(filePath)).getAbsolutePath();
        SCXML stateMachine = SCXMLParser.parse(new File(absolutePath).toURI().toURL(), null);
        SCXMLExecutor executor = new SCXMLExecutor();
        ELEvaluator elEvaluator = new ELEvaluator();
        ELContext context = new ELContext();
        this.listener = new StateMachineListener();

        this.setEvaluator(elEvaluator);
        this.setStateMachine(stateMachine);
        this.setRootContext(context);
        this.addListener(stateMachine, listener);
    }

    public StateMachineListener getListener(){
        return listener;
    }

    /**
     * Reset the state machine, set the initial variables, and trigger the
     * initial events
     *
     * @throws org.apache.commons.scxml.model.ModelException
     */
    public void resetStateMachine(Set<String> varsOut, Map<String, String> initialVariablesMap, List<String> initialEvents) throws ModelException {
        // Go to the initial state
        this.reset();

        // Set the initial variables values
        for (String var : varsOut) {
            this.getRootContext().set(var, "");
        }

        for (Map.Entry<String, String> entry : initialVariablesMap.entrySet()) {
            this.getRootContext().set(entry.getKey(), entry.getValue());
        }

        listener.reset();
        this.go();
        fireEvents(initialEvents);
    }

    /**
     * Executes a list of given events. The format is
     * [beforeState]-event-[afterState] with the before and after states and
     * their separators optional
     *
     * @param commaSeparatedEvents
     * @throws ModelException
     */
    public void fireEvents(String commaSeparatedEvents) throws ModelException {
        if (commaSeparatedEvents == null) {
            return;
        }
        commaSeparatedEvents = commaSeparatedEvents.trim();
        if (commaSeparatedEvents == null || commaSeparatedEvents.length() == 0) {
            return;
        }

        String[] events = commaSeparatedEvents.split(",");
        for (String event : events) {
            log.debug("Firing event: " + event);
            String eventName = event;
            if (eventName.contains("-")) {
                String[] parts = event.split("-");
                eventName = parts[1];
            }
            log.debug("EventName:" + eventName);
            this.triggerEvent(new TriggerEvent(eventName, TriggerEvent.SIGNAL_EVENT));
        }
    }


    private void fireEvents(List<String> events) throws ModelException {
        if (events == null) {
            return;
        }

        for (String event : events) {
            log.debug("Firing event: " + event);
            String eventName = event;
            if (eventName.contains("-")) {
                String[] parts = event.split("-");
                eventName = parts[1];
            }
            log.debug("EventName:" + eventName);
            this.triggerEvent(new TriggerEvent(eventName, TriggerEvent.SIGNAL_EVENT));
        }
    }

    public void findEvents( ArrayList<String> positive, ArrayList<String> negative) throws ModelException,
            SCXMLExpressionException, IOException {
        positive.clear();
        negative.clear();
        TransitionTarget currentState = listener.getCurrentState();
        if (currentState == null) {
            throw new ModelException("Reached a null state");
        }
        List<Transition> transitions = currentState.getTransitionsList();
        for (Transition transition : transitions) {
            String condition = transition.getCond();
            List<TransitionTarget> targets = transition.getTargets();

            // In our case we should only have one target always
            if (targets == null) {
                throw new IOException("Found null targets for transition: " + transition.getEvent() + " in state: "
                        + currentState.getId());
            }

            if (targets.size() > 1 || targets.isEmpty()) {
                throw new IOException("Found incorrect number of targets:" + targets.size() + "for transition: "
                        + transition.getEvent() + " in state: " + currentState.getId());
            }

            String nextStateId = targets.get(0).getId();
            String transitionCode = currentState.getId() + "-" + transition.getEvent() + "-" + nextStateId;
            if (condition == null) {
                positive.add(transitionCode);
            } else {
                Boolean result;
                try {
                    result = (Boolean) this.getEvaluator().eval(this.getRootContext(), condition);
                } catch (Exception ex) {
                    throw new RuntimeException("Error while evaluating the condition: " + condition + " in state: " + currentState.getId(), ex);
                }
                if (result == null) {
                    throw new ModelException("Condition: " + condition + " evaluates to null");
                }

                if (result) {
                    positive.add(transitionCode);
                } else {
                    negative.add(transitionCode);
                }
            }
        }
    }

    private HashMap<String, String> readVarsOut(Set<String> varsOut) {
        HashMap<String, String> result = new HashMap<String, String>();
        for (String varName : varsOut) {
            result.put(varName, (String) this.getRootContext().get(varName));
        }
        return result;
    }

    /**
     * Check all the variables in the context. Generate a state with a list of
     * variables correctly assigned
     */
    public ArrayList<PossibleState> findPossibleStates(Set<String> varNames) throws ModelException, SCXMLExpressionException, IOException {
        log.debug("findPossibleStates");
        ArrayList<PossibleState> possiblePositiveStates = new ArrayList<PossibleState>();
        ArrayList<String> positive = new ArrayList<String>();
        ArrayList<String> negative = new ArrayList<String>();
        findEvents(positive, negative);
        Map<String, String> vars = readVarsOut(varNames);
        for (String state : positive) {
            PossibleState possibleState = new PossibleState();
            String[] parts = state.split("-");
            possibleState.id = parts[0];
            possibleState.nextStateName = parts[2];
            possibleState.transitionEvent = parts[1];
            possibleState.variablesAssignment.putAll(vars);
            possiblePositiveStates.add(possibleState);
        }
        return possiblePositiveStates;
    }

    public void traceDepth(ArrayList<ArrayList<PossibleState>> possiblePositiveStatesList, Set<String> varsOut, Map<String, String> initialVariablesMap, List<String> initialEvents) throws ModelException, IOException, SCXMLExpressionException {
        log.debug("TraceDepth");
        if (possiblePositiveStatesList.isEmpty()) {
            this.resetStateMachine(varsOut, initialVariablesMap, initialEvents);
        } else {
            ArrayList<PossibleState> states = possiblePositiveStatesList.get(possiblePositiveStatesList.size() - 1);
            PossibleState initialState = states.get(0);
            this.getStateMachine().setInitial(initialState.nextStateName);
            this.getStateMachine().setInitialTarget((TransitionTarget) this.getStateMachine().getTargets().get(initialState.nextStateName));
            for (Map.Entry<String, String> var : initialState.variablesAssignment.entrySet()) {
                this.getRootContext().set(var.getKey(), var.getValue());
            }
            this.reset();
        }

        log.debug("Loop start");

        while (listener.getCurrentState() == null
                || (listener.getCurrentState() != null && !listener.getCurrentState().getId().equals("end"))) {
           log.debug("ALL AFTER RESET: " + possiblePositiveStatesList);
            // Replay the last initial state
            /*for (ArrayList<PossibleState> states : possiblePositiveStatesList)*/
            if (possiblePositiveStatesList.size() > 0) {
                ArrayList<PossibleState> states = possiblePositiveStatesList.get(possiblePositiveStatesList.size() - 1);
                PossibleState initialState = states.get(0);
                log.debug("**RESET");
                log.debug("**SET INIT TO:" + initialState.nextStateName);
                this.getStateMachine().setInitial(initialState.nextStateName);
                this.getStateMachine().setInitialTarget((TransitionTarget) this.getStateMachine().getTargets().get(initialState.nextStateName));
                for (Map.Entry<String, String> var : initialState.variablesAssignment.entrySet()) {
                    this.getRootContext().set(var.getKey(), var.getValue());
                }
                this.reset();

                log.debug("current state:" + listener.getCurrentState().getId());

                if (!initialState.varsInspected) {
                    HashMap<String, String> varsVals = readVarsOut(varsOut);
                    log.debug("varsVals has " + varsVals);
                    log.debug("Vars not initialzed, initializing");
                    if (varsVals == null || varsVals.isEmpty()) {
                        throw new IOException("Empty or null varsVals");
                    }
                    for (Map.Entry<String, String> var : varsVals.entrySet()) {
                        String nextVal = var.getValue();
                        log.debug("key:" + var.getKey());
                        log.debug("val:" + nextVal);
                        if (nextVal.length() > 5 && nextVal.startsWith("set:{")) {
                            // Remove the set:{ and }
                            String[] vals = nextVal.substring(5, nextVal.length() - 1).split(",");

                            // Delete this state from the list
                            states.remove(0);
                            for (String val : vals) {
                                PossibleState possibleState = new PossibleState();
                                possibleState.id = initialState.id;
                                possibleState.nextStateName = initialState.nextStateName;
                                possibleState.transitionEvent = initialState.transitionEvent;
                                possibleState.variablesAssignment.putAll(initialState.variablesAssignment);
                                possibleState.variablesAssignment.put(var.getKey(), val);
                                possibleState.varsInspected = true;
                                states.add(0, possibleState);
                                log.debug("Adding:" + possibleState);
                            }
                        } else {
                            states.get(0).variablesAssignment.put(var.getKey(), nextVal);
                            states.get(0).varsInspected = true;
                        }
                    }
                    initialState = states.get(0);
                }

                // Set the variables
                for (Map.Entry<String, String> var : initialState.variablesAssignment.entrySet()) {
                    this.getRootContext().set(var.getKey(), var.getValue());
                }
            }

            log.debug("ALL BEFORE: " + possiblePositiveStatesList);

            ArrayList<PossibleState> nextPositiveStates = findPossibleStates(varsOut);
            //System.err.println("nextPositiveStates: " + nextPositiveStates);

            possiblePositiveStatesList.add(nextPositiveStates);
            log.debug("ALL AFTER: " + possiblePositiveStatesList);
        }

        // Remove end
        possiblePositiveStatesList.remove(possiblePositiveStatesList.size() - 1);
    }

    /**
     * Do a depth first search looking for scenarios
     *
     * @throws ModelException
     * @throws SCXMLExpressionException
     * @throws IOException
     */
    public void searchForScenariosDFS(Queue queue, Set<String> varsOut, Map<String, String> initialVariablesMap, List<String> initialEvents) throws ModelException, SCXMLExpressionException, IOException, SAXException {
        log.info("Search for scenarios using depth first search");

        ArrayList<ArrayList<PossibleState>> possiblePositiveStatesList = new ArrayList<ArrayList<PossibleState>>();
        ArrayList<String> currentStates = new ArrayList<String>();
        ArrayList<Integer> activePostiveState = new ArrayList<Integer>();

        // First we have to generate the first level in the depth, so that we have something to start
        // the recursion from
        log.debug("Searching for the initial next possible states");

        traceDepth(possiblePositiveStatesList, varsOut, initialVariablesMap, initialEvents);
        log.debug("Initial depth trace: " + possiblePositiveStatesList);

        int scenariosCount = 0;
        // Now we have the initial list with sets decompressed
        queue.add(readVarsOut(varsOut));
        while (true) {
            // Recursively delete one node from the end
            boolean empty;
            do {
                if (possiblePositiveStatesList.isEmpty()) {
                    empty = true;
                    break;
                }
                empty = false;
                int lastDepth = possiblePositiveStatesList.size() - 1;
                PossibleState removed = possiblePositiveStatesList.get(lastDepth).remove(0);

                log.debug("Removing: " + removed.nextStateName);
                if (possiblePositiveStatesList.get(lastDepth).isEmpty()) {
                    log.debug("Empty at level: " + possiblePositiveStatesList.size());
                    possiblePositiveStatesList.remove(possiblePositiveStatesList.size() - 1);
                    empty = true;
                }
            } while (empty);

            if (empty) {
                // We're done
                break;
            }

            log.debug("**After removing, depth trace: " + possiblePositiveStatesList);
            traceDepth(possiblePositiveStatesList, varsOut, initialVariablesMap, initialEvents);
            log.debug("**After finding next, depth trace: " + possiblePositiveStatesList);

            queue.add(readVarsOut(varsOut));

            scenariosCount++;
            if (scenariosCount % 10000 == 0) {
                log.info("Queue size=" + queue.size());
            }

            if (queue.size() > 1000000) {
                log.info("Queue size " + queue.size() + " waiting for 1 sec");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    // Do nothing, since this is the main thread....
                }
            }
        }
    }

    public Iterable<List<String>> searchForScenarios(Set<String> varsOut, Map<String, String> initialVariablesMap, List<String> initialEvents, int maxEventReps, int maxScenarios, int lengthOfScenario) throws ModelException, SCXMLExpressionException, IOException, SAXException {

        /*
         * Initialize:
          * executor, a new Executor with its own state
          * stack, a stack of Lists of events
          * numberOfScenarios generated, a counter for number of scenarios generated
          * positiveEvents and negativeEvents, temp lists of positive and negative events
          *     eventually used to populate stack
          *
         */
        ArrayDeque<List<String>> stack = new ArrayDeque<List<String>>();
        int numberOfScenariosGenerated = 0;
        ArrayList<String> positiveEvents = new ArrayList<String>();
        ArrayList<String> negativeEvents = new ArrayList<String>();
        resetStateMachine(varsOut, initialVariablesMap, initialEvents);

        /*
         * Get the set of reachable (positive) states and non-reachable (negative) states
         * from the current state of the executor.
         */
        findEvents(positiveEvents, negativeEvents);

        // Each positive event returned is immediately reachable
        // Add it as a one-event path to the stack
        for (String event : positiveEvents) {
            ArrayList<String> singleEventList = new ArrayList<String>();
            singleEventList.add(event);
            singleEventList = pruneEvents(singleEventList, initialEvents, maxEventReps, lengthOfScenario);

            if (singleEventList != null) {
                stack.push(singleEventList);
                printEvents("+ve:", singleEventList, initialEvents);
                numberOfScenariosGenerated++;
            }
        }

        /*
         * This is only printing (no negative event lists are added to the stack)
         */
        // Check the -ve events
        for (String event : negativeEvents) {
            ArrayList<String> singleEventList = new ArrayList<String>();
            singleEventList.add(event);
            singleEventList = pruneEvents(singleEventList, initialEvents, maxEventReps, lengthOfScenario);

            if (singleEventList != null) {
                printEvents("-ve:", singleEventList, initialEvents);
                numberOfScenariosGenerated++;
            }
        }

        // Until stack is empty or we max out the number of scenarios
        while (stack.size() > 0 && numberOfScenariosGenerated < maxScenarios) {
            List<String> scenario = stack.pop();

            log.debug("Searching for more scenarios using: " + scenario);

            // Reset state machine to initial state
            // Fire the scenario
            // Find events reachable after the scenario is executed
            resetStateMachine(varsOut, initialVariablesMap, initialEvents);
            fireEvents(scenario);
            findEvents( positiveEvents, negativeEvents);

            // For each positive event now immediately executable, add the
            // executed scenario + the positive event to the stack
            for (String event : positiveEvents) {
                ArrayList<String> eventList = new ArrayList<String>();
                log.debug("Scenario:" + scenario + " new event:" + event);
                eventList.addAll(scenario);
                eventList.add(event);
                eventList = pruneEvents(eventList, initialEvents, maxEventReps, lengthOfScenario);

                if (eventList != null) {
                    stack.push(eventList);
                    printEvents("+ve:", eventList, initialEvents);
                    numberOfScenariosGenerated++;
                }
            }

            // Check the -ve events
            for (String event : negativeEvents) {
                ArrayList<String> eventList = new ArrayList<String>();
                eventList.addAll(scenario);
                eventList.add(event);
                eventList = pruneEvents(eventList, initialEvents, maxEventReps, lengthOfScenario);

                if (eventList != null) {
                    printEvents("-ve:", eventList, initialEvents);
                    numberOfScenariosGenerated++;
                }
            }
        }

        return stack;
    }

    /**
     * Delete the scenario if an event is repeated more than maxEventReps times
     *
     * @param eventList
     * @return
     */
    private ArrayList<String> pruneEvents(ArrayList<String> eventList, List<String> initialEventsList, int maxEventReps, int lengthOfScenario) {
        // Count the number of repetitions of every event
        ArrayList<String> all = new ArrayList<String>();
        all.addAll(initialEventsList);
        all.addAll(eventList);

        if (all.size() > lengthOfScenario) {
            return null;
        }

        HashMap<String, Integer> count = new HashMap<String, Integer>();
        for (String event : all) {
            Integer counter = count.get(event);
            if (counter == null) {
                counter = 0;
            }
            counter++;
            count.put(event, counter);
            if (counter > maxEventReps) {
                return null;
            }
        }
        return eventList;
    }

    private void printEvents(String type, ArrayList<String> events, List<String> initialEventsList) {
        StringBuilder b = new StringBuilder();
        b.append(type);

        if (initialEventsList != null) {
            b
                    .append(initialEventsList)
                    .append(",");
        }

        boolean firstEvent = true;
        for (String event : events) {
            if (firstEvent) {
                firstEvent = false;
            } else {
                b.append(",");
            }
            b.append(event);
        }

        log.info(b);

    }
}