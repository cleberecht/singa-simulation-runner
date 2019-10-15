package bio.singa.simulation.runner;

import bio.singa.core.utility.Pair;
import bio.singa.mathematics.algorithms.graphs.DisconnectedSubgraphFinder;
import bio.singa.mathematics.algorithms.graphs.NeighbourhoodExtractor;
import bio.singa.mathematics.algorithms.graphs.ShortestPathFinder;
import bio.singa.mathematics.graphs.model.GenericEdge;
import bio.singa.mathematics.graphs.model.GenericGraph;
import bio.singa.mathematics.graphs.model.GenericNode;
import bio.singa.mathematics.graphs.model.GraphPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author cl
 */
public class VariationSplitter {

    private static final Logger logger = LoggerFactory.getLogger(SimulationRunner.class);

    public static void main(String[] args) throws IOException {

        VariationSplitter splitter = new VariationSplitter();

        Path wd = Paths.get("/home/leberech/git/model-data/data/pool_processing/restricted");
        List<String> pcNames;
        Path pcPoolPath = wd.resolve("pool_c1.txt");
        try {
            pcNames = Files.readAllLines(pcPoolPath);
        } catch (IOException e) {
            logger.error("unable to read pc pool file {}", pcPoolPath, e);
            return;
        }
        System.out.println(pcNames);

        // get simulation file
        Path baseSetupPath = wd.resolve("base_restricted_c1.json");
        String simulationDocument;
        try {
            simulationDocument = String.join("\n", Files.readAllLines(baseSetupPath));
        } catch (IOException e) {
            logger.error("unable to read simulation file {}", baseSetupPath, e);
            return;
        }

        Map<Pair<Integer>, String> alternativeValues = splitter.collectAlternativeValues(simulationDocument);
        Map<Pair<Integer>, List<String>> splitValues = splitter.spiltAlternativeValues(alternativeValues);
        GenericGraph<List<String>> graph = splitter.connectInitialGraph(splitValues);
        splitter.split(graph, alternativeValues.size(), 9);

//        GraphDisplayApplication.graph = graph;
//        GraphDisplayApplication.renderer.getRenderingOptions().setDisplayText(true);
//        GraphDisplayApplication.renderer.getRenderingOptions().setTextExtractor(node -> ((GenericNode) node).getContent().toString());
//        Application.launch(GraphDisplayApplication.class);

        List<GenericNode<List<String>>> leaves = graph.getAllNodes(node -> node.getNeighbours().size() == 1 && !node.getContent().isEmpty());
        List<GraphPath<GenericNode<List<String>>, GenericEdge<List<String>>>> paths = new ArrayList<>();
        for (GenericNode<List<String>> leaf : leaves) {
            GraphPath<GenericNode<List<String>>, GenericEdge<List<String>>> path = ShortestPathFinder.findBasedOnPredicate(graph, graph.getNode(0), node -> node.equals(leaf));
            paths.add(path);
            System.out.println(path.getNodes());
        }

        // create copies of setup file
        Iterator<String> pcNameIterator = pcNames.iterator();
        List<Map.Entry<Pair<Integer>, List<String>>> entries = sortEntries(splitValues);
        for (GraphPath<GenericNode<List<String>>, GenericEdge<List<String>>> path : paths) {
            int depth = 1;
            String resultingDocument = simulationDocument;
            // replace alternative values (from to indices) with new value
            for (Map.Entry<Pair<Integer>, List<String>> indices : entries) {
                String initialPart = resultingDocument.substring(0, indices.getKey().getFirst());
                String endPart = resultingDocument.substring(indices.getKey().getSecond());
                resultingDocument = initialPart + path.getNodes().get(depth).getContent().stream().collect(Collectors.joining(", ", "[ ", " ]")) + endPart;
                depth++;
            }
            // save with pc names in list
            Files.write(wd.resolve(pcNameIterator.next() + ".txt"), resultingDocument.getBytes());
        }

        // TODO (sugar) if remaining splits is divisible by next list size prefer that list

    }

    private static List<Map.Entry<Pair<Integer>, List<String>>> sortEntries(Map<Pair<Integer>, List<String>> splitValues) {
        List<Map.Entry<Pair<Integer>, List<String>>> entries = new ArrayList<>(splitValues.entrySet());
        Comparator<Map.Entry<Pair<Integer>, List<String>>> comparator = Comparator.comparingInt(entry -> entry.getKey().getFirst());
        entries.sort(comparator.reversed());
        return entries;
    }


    private Map<Pair<Integer>, String> collectAlternativeValues(String simulationDocument) {
        Map<Pair<Integer>, String> alternativeValues = new HashMap<>();
        Pattern alternativeValuePattern = Pattern.compile("\"alternative-values\" : (.*?\\])");
        Matcher matcher = alternativeValuePattern.matcher(simulationDocument);
        while (matcher.find()) {
            alternativeValues.put(new Pair<>(matcher.start(1), matcher.end(1)), matcher.group(1));
        }
        return alternativeValues;
    }

    private Map<Pair<Integer>, List<String>> spiltAlternativeValues(Map<Pair<Integer>, String> alternativeValues) {
        Map<Pair<Integer>, List<String>> splitValues = new HashMap<>();
        for (Map.Entry<Pair<Integer>, String> entry : alternativeValues.entrySet()) {
            Pair<Integer> indices = entry.getKey();
            String values = entry.getValue();
            Pattern alternativeValuePattern = Pattern.compile("([0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?)");
            Matcher matcher = alternativeValuePattern.matcher(values);
            List<String> splitValueList = new ArrayList<>();
            while (matcher.find()) {
                splitValueList.add(matcher.group(1));
            }
            splitValues.put(indices, splitValueList);
        }
        return splitValues;
    }

    private GenericGraph<List<String>> connectInitialGraph(Map<Pair<Integer>, List<String>> splitValues) {
        GenericGraph<List<String>> graph = new GenericGraph<>();
        GenericNode<List<String>> root = graph.addNode(Collections.emptyList());
        GenericNode<List<String>> previous = null;
        List<Map.Entry<Pair<Integer>, List<String>>> entries = sortEntries(splitValues);
        System.out.println(entries);
        for (Map.Entry<Pair<Integer>, List<String>> value : entries) {
            GenericNode<List<String>> current = graph.addNode(value.getValue());
            if (previous == null) {
                graph.addEdgeBetween(root, current);
            } else {
                graph.addEdgeBetween(previous, current);
            }
            previous = current;
        }
        return graph;
    }

    private void split(GenericGraph<List<String>> graph, int depth, int times) {
        for (int i = 1; i < times; i++) {
            split(graph, depth);
        }
    }

    private void split(GenericGraph<List<String>> graph, int depth) {
        Optional<GenericNode<List<String>>> rootOpt = graph.getNodeWithContent(Collections.emptyList());
        if (!rootOpt.isPresent()) {
            return;
        }
        GenericNode<List<String>> root = rootOpt.get();
        // first shell
        for (int i = 1; i < depth; i++) {
            List<GenericNode<List<String>>> shell = NeighbourhoodExtractor.extractShell(graph, root, i);
            for (GenericNode<List<String>> node : shell) {
                List<String> strings = node.getContent();
                if (strings.size() > 1) {
                    List<GenericNode<List<String>>> neighbours = node.getNeighbours();
                    for (GenericNode<List<String>> neighbour : neighbours) {
                        // determine previous splits or root
                        if (neighbour.getContent().size() <= 1) {
                            splitAt(graph, node, neighbour);
                            return;
                        }
                    }
                }
            }
        }


    }

    private void splitAt(GenericGraph<List<String>> graph, GenericNode<List<String>> current, GenericNode<List<String>> previous) {

        List<String> currentContent = current.getContent();
        String next = currentContent.iterator().next();
        // remove element from selected node
        currentContent.remove(next);
        // add new node
        GenericNode<List<String>> newNode = graph.addNode(Collections.singletonList(next));
        graph.addEdgeBetween(previous, newNode);

        for (GenericNode<List<String>> neighbour : current.getNeighbours()) {
            if (neighbour != previous) {
                // cut between current and neighbor
                GenericGraph<List<String>> copy = graph.getCopy();
                copy.removeEdge(current, neighbour);
                List<GenericGraph<List<String>>> subgraphs = DisconnectedSubgraphFinder.findDisconnectedSubgraphs(copy);
                for (GenericGraph<List<String>> subgraph : subgraphs) {
                    // get subgraph with cut node
                    if (subgraph.containsNode(neighbour)) {
                        graph.appendGraph(newNode, neighbour, subgraph);
                        break;
                    }
                }
                break;
            }
        }


    }


}
