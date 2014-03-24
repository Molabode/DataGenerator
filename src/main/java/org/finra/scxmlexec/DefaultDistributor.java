package org.finra.scxmlexec;

import org.apache.commons.scxml.io.SCXMLParser;
import org.apache.commons.scxml.model.ModelException;
import org.apache.commons.scxml.model.SCXML;
import org.apache.log4j.Logger;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by robbinbr on 3/24/14.
 */
public class DefaultDistributor implements SearchDistributor {

    protected static final Logger log = Logger.getLogger(DefaultDistributor.class);

    private int threadCount = 1;
    private final Queue<HashMap<String, String>> queue = new ConcurrentLinkedQueue<HashMap<String, String>>();
    private Thread outputThread;
    private DataConsumer userDataOutput = new DefaultOutput(System.out);
    private String stateMachineText;

    @Override
    public void setOptions(Map<String, String> options) throws RequiredOptionException {
        threadCount = options.get("threadCount") == null ? 1 : Integer.parseInt(options.get("threadCount"));
    }

    @Override
    public void setStateMachineText(String stateMachine) {
        this.stateMachineText = stateMachine;
    }

    @Override
    public void setDataConsumer(DataConsumer dataConsumer) {
        this.userDataOutput = dataConsumer;
    }

    @Override
    public void distribute(List<SearchProblem> searchProblemList) {

        // Start output thread (consumer)
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

        // Start search threads (producers)
        ExecutorService threadPool = Executors.newFixedThreadPool(threadCount);
        for (SearchProblem problem : searchProblemList) {
            Runnable worker = null;
            try {
                InputStream is = new ByteArrayInputStream(stateMachineText.getBytes());
                SCXML stateMachine = SCXMLParser.parse(new InputSource(is), null);
                worker = new SearchWorker(problem, stateMachine, queue);
            } catch (ModelException e) {
                log.error("Error while initializing SearchWorker threads", e);
            } catch (SAXException e) {
                log.error("Error while initializing SearchWorker threads", e);
            } catch (IOException e) {
                log.error("Error while initializing SearchWorker threads", e);
            }

            threadPool.execute(worker);
        }

        // Wait for threadPool shutdown to complete
        threadPool.shutdown();
        while (!threadPool.isTerminated()) {
            try {
                //log.debug("process() is waiting for thread pool to terminate");
                Thread.sleep(10);
            } catch (InterruptedException ex) {
            }
        }

        // Wait for queue to empty (finish processing any pending output)
        while (!queue.isEmpty()) {
            try {
                //log.debug("process() is waiting for queue to empty");
                Thread.sleep(10);
            } catch (InterruptedException ex) {
            }
        }
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
