package org.finra.scxmlexec;

import org.apache.commons.scxml.SCXMLExpressionException;
import org.apache.commons.scxml.model.ModelException;
import org.apache.log4j.Logger;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Created by robbinbr on 3/14/14.
 */


public class SearchWorker implements Runnable {

    protected static final Logger log = Logger.getLogger(SearchWorker.class);

    private PossibleState initialState;
    private Queue queue;
    private DataGeneratorExecutor executor;
    private Set<String> varsOut;
    private Map<String, String> initialVariablesMap;
    private List<String> initialEventsList;


    public SearchWorker(PossibleState initialState, Queue queue, DataGeneratorExecutor executor, Set<String> varsOut, Map<String, String> initialVariablesMap, List<String> initialEventsList) {
        this.initialState = initialState;
        this.queue = queue;
        this.executor = executor;
        this.varsOut = varsOut;
        this.initialVariablesMap = initialVariablesMap;
        this.initialEventsList = initialEventsList;
    }

    @Override
    public void run() {
        try {
            executor.searchForScenariosDFS(initialState, queue, varsOut, initialVariablesMap, initialEventsList);
        } catch (Exception exc){
            log.error("Exception has occurred during DFS worker thread", exc);
        }
    }

}
