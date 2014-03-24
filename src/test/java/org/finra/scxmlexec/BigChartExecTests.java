package org.finra.scxmlexec;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by robbinbr on 3/3/14.
 */
public class BigChartExecTests {

    private ChartExec exec;

    @Before
    public void setUpChartExec() {
        exec = new ChartExec();
        exec.setInputFileName("src/test/resources/big.xml");
        exec.setUserDataOutput(new TestConsumer());
    }

    @Test
    public void testProcess() throws Exception {
        TestConsumer consumer = new TestConsumer();
        exec.setUserDataOutput(consumer);
        exec.setBootstrapMin(3);

        exec.process();

        System.out.println(consumer.getData());

        Assert.assertEquals(9, consumer.getData().size());
    }

    @Test
    public void testProcessParallel() throws Exception {
        TestConsumer consumer = new TestConsumer();
        exec.setUserDataOutput(consumer);
        exec.setBootstrapMin(3);
        exec.setDistributorOption("threadCount", "1");
        exec.process();

        System.out.println(consumer.getData());

        Assert.assertEquals(9, consumer.getData().size());
    }

    private class TestConsumer implements DataConsumer {
        private List<Map<String, String>> data = new ArrayList<Map<String, String>>();

        @Override
        public void consume(HashMap<String, String> row) {
            data.add(row);
            System.out.println("Output saw a : " + row);
        }

        public List<Map<String, String>> getData() {
            return data;
        }
    }

}