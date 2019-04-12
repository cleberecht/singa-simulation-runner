package bio.singa.simulation.runner;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * @author cl
 */
public class ObservationFormat extends ArrayList<String> {

    ObservationFormat() { super(Arrays.asList("csv", "json")); }

}
