package bio.singa.simulation.runner;

import bio.singa.core.utility.Pair;
import bio.singa.exchange.features.FeatureDataset;
import bio.singa.exchange.features.FeatureRepresentation;
import bio.singa.features.model.*;
import bio.singa.mathematics.combinatorics.StreamPermutations;

import javax.measure.Quantity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author cl
 */
public class VariationManager {

    // map that stores the feature identifier mapping to the min and max index of the features variations
    private Map<Integer, Pair<Integer>> featureState;
    // map that stores a mapping of the index (numbered in order of occurrence) to the feature identifier
    private Map<Integer, Integer> indexIdentifierMap;
    // all possible permutations, outer list represents a set of variation for a run, inner list contains
    // the possible indices of the feature values, whose indices correspond to the index identifier mapping
    private List<List<Integer>> permutations;
    // the current permutation that is applied
    private Iterator<List<Integer>> permutationIterator;
    // a count for the current variation
    private int currentVariationIndex;
    // map for time stamped folder to feature set
    private Map<String, Map<Integer, Object>> variationMap;
    private List<Feature<?>> currentVariationSet;

    public VariationManager() {
        featureState = new HashMap<>();
        variationMap = new HashMap<>();
        currentVariationIndex = 0;
        determineVariableFeatures();
        determinePossibleVariations();
    }

    public int getCurrentVariationIndex() {
        return currentVariationIndex;
    }

    public int getPossibleVariations() {
        return permutations.size();
    }

    private void determineVariableFeatures() {
        // collect all features that have variations associated to them
        for (QualitativeFeature<?> qualitativeFeature : FeatureRegistry.getQualitativeFeatures()) {
            if (!qualitativeFeature.getAlternativeContents().isEmpty()) {
                featureState.put(qualitativeFeature.getIdentifier(), new Pair<>(0, qualitativeFeature.getAlternativeContents().size()));
            }
        }
        for (QuantitativeFeature<?> quantitativeFeature : FeatureRegistry.getQuantitativeFeatures()) {
            if (!quantitativeFeature.getAlternativeContents().isEmpty()) {
                featureState.put(quantitativeFeature.getIdentifier(), new Pair<>(0, quantitativeFeature.getAlternativeContents().size()));
            }
        }
        for (ScalableQuantitativeFeature<?> scalableQuantitativeFeature : FeatureRegistry.getScalableQuantitativeFeatures()) {
            if (!scalableQuantitativeFeature.getAlternativeContents().isEmpty()) {
                featureState.put(scalableQuantitativeFeature.getIdentifier(), new Pair<>(0, scalableQuantitativeFeature.getAlternativeContents().size()));
            }
        }
    }

    private void determinePossibleVariations() {
        indexIdentifierMap = new HashMap<>();
        int featureIndex = 0;
        // generate base indices
        List<List<Integer>> indices = new ArrayList<>();
        for (Map.Entry<Integer, Pair<Integer>> entry : featureState.entrySet()) {
            Pair<Integer> range = entry.getValue();
            Integer featureIdentifier = entry.getKey();
            indexIdentifierMap.put(featureIndex, featureIdentifier);
            featureIndex++;
            indices.add(IntStream.range(range.getFirst(), range.getSecond())
                    .boxed()
                    .collect(Collectors.toList()));
        }
        // permute
        permutations = StreamPermutations.permutations(indices);
        permutationIterator = permutations.iterator();
    }

    public void nextVariationSet() {
        currentVariationSet = new ArrayList<>();
        List<Integer> permuation = permutationIterator.next();
        for (int index = 0; index < permuation.size(); index++) {
            int featureIdentifier = indexIdentifierMap.get(index);
            Feature<?> feature = FeatureRegistry.get(featureIdentifier);
            feature.setAlternativeContent(permuation.get(index));
            currentVariationSet.add(feature);
        }
        currentVariationIndex++;
    }

    public boolean hasVariationsLeft() {
        return permutationIterator.hasNext();
    }

    public Path generateJsonLog(Path timestampedFolder) {
        try {
            String variations = FeatureDataset.generateVariableFeatureLog();
            Path logPath = timestampedFolder.resolve("variations.json");
            Files.write(logPath, variations.getBytes());
            return logPath;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write variation log to " + timestampedFolder + ".", e);
        }
    }

    public void determineProcessedVariations(Path targetDirectory) {
        variationMap.clear();
        // traverse all observation directories
        try (DirectoryStream<Path> observationDirectoryStream = Files.newDirectoryStream(targetDirectory)) {
            for (Path observationDirectoryPath : observationDirectoryStream) {
                if (Files.isDirectory(observationDirectoryPath)) {
                    String timeStamp = observationDirectoryPath.getFileName().toString();
                    Path variationFilePath = observationDirectoryPath.resolve("variations.json");
                    // check if there is a variation log
                    if (Files.exists(variationFilePath)) {
                        String json = String.join("", Files.readAllLines(variationFilePath));
                        List<FeatureRepresentation<?>> features = FeatureDataset.fromDatasetRepresentation(json);
                        HashMap<Integer, Object> featureValues = new HashMap<>();
                        variationMap.put(timeStamp, featureValues);
                        for (FeatureRepresentation<?> feature : features) {
                            featureValues.put(feature.getIdentifier(), feature.fetchContent());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to retrieve simulation paths from " + targetDirectory + ".", e);
        }
    }

    public String wasAlreadyProcessedIn() {
        if (variationMap.isEmpty()) {
            return "";
        }
        // for all processed variations
        for (Map.Entry<String, Map<Integer, Object>> processedEntry : variationMap.entrySet()) {
            boolean allValuesAreTheSame = true;
            // for each current feature
            for (Feature<?> currentFeature : currentVariationSet) {
                int currentIdentifier = currentFeature.getIdentifier();
                // get corresponding feature in processed variation
                Object processedVariant = processedEntry.getValue().get(currentIdentifier);
                // and their values are not the same, skip the current processed variation
                Object currentContent = currentFeature.getContent();
                if (currentContent instanceof Quantity) {
                    currentContent = ((Quantity) currentContent).getValue().doubleValue();
                }
                if (!currentContent.equals(processedVariant)) {
                    allValuesAreTheSame = false;
                    break;
                }
            }
            if (allValuesAreTheSame) {
                return processedEntry.getKey();
            }
        }
        return "";
    }

    public void consoleLogVariations() {
        for (Map.Entry<Integer, Pair<Integer>> entry : featureState.entrySet()) {
            int featureIdentifier = entry.getKey();
            System.out.println("  " + FeatureRegistry.get(featureIdentifier));
        }
    }

}
