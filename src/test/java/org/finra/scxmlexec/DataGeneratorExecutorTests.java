package org.finra.scxmlexec;

import org.apache.commons.scxml.SCXMLExpressionException;
import org.apache.commons.scxml.model.ModelException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by robbinbr on 3/3/14.
 */
public class DataGeneratorExecutorTests {

    private DataGeneratorExecutor executor;

    @Before
    public void setUpExecutor() throws ModelException, SAXException, IOException {
        URL testXmlURL=this.getClass().getResource("/test.xml");
        executor = new DataGeneratorExecutor(testXmlURL);
    }

    @Test
    public void testFindEvents() throws ModelException, SCXMLExpressionException, IOException {
        List<String> positive = new ArrayList<String>();
        List<String> negative = new ArrayList<String>();

        String startState = "start";
        String event1 = "RECORD_TYPE";
        String state1 = "RECORD_TYPE";

        executor.findEvents(positive, negative);
        Assert.assertTrue(startState + "-" + event1 + "-" + state1 + " not found in positive events", positive.contains(startState + "-" + event1 + "-" + state1));

        List eList = new ArrayList<String>();
        eList.add(event1);
        executor.fireEvents(eList);
        executor.findEvents(positive, negative);
        System.out.println(positive);
    }
}