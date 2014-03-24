package org.finra.scxmlexec;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Created by robbinbr on 3/3/14.
 */
public class SearchProblemTests {

    @Test
    public void testToJsonAndFromJson() throws Exception {
        ChartExec chartExec = new ChartExec();
        chartExec.setInputFileName("src/test/resources/test.xml");
        chartExec.setBootstrapMin(3);
        List<SearchProblem> problems = chartExec.prepare();

        Assert.assertEquals(3, problems.size());

        SearchProblem one = problems.get(0);
        String json = one.toJson();
        SearchProblem oneFromJson = SearchProblem.fromJson(json);

        Assert.assertTrue(oneFromJson.getVarsOut().equals(one.getVarsOut()));
        Assert.assertTrue(oneFromJson.getInitialState().events.equals(one.getInitialState().events));
        Assert.assertTrue(oneFromJson.getInitialState().variablesAssignment.equals(one.getInitialState()
                .variablesAssignment));
    }
}