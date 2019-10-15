package bio.singa.simulation.runner;

import bio.singa.core.utility.Pair;
import bio.singa.features.model.*;
import bio.singa.mathematics.combinatorics.StreamPermutations;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author cl
 */
public class VariationManager {

    private Map<Integer, Pair<Integer>> featureState;

    private int currentVariation;

    private Map<Integer, Integer> indexIdentifierMap;
    private List<List<Integer>> permutations;
    private Iterator<List<Integer>> permutationIterator;

    public VariationManager() {
        featureState = new HashMap<>();
        currentVariation = 0;
        determineVariableFeatures();
        determinePossibleVariations();
    }

    public int getCurrentVariation() {
        return currentVariation;
    }

    public int getPossibleVariations() {
        return permutations.size();
    }

    private void determineVariableFeatures() {
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
        int featureCounter = 0;
        // generate base indices
        List<List<Integer>> indices = new ArrayList<>();
        for (Map.Entry<Integer, Pair<Integer>> entry : featureState.entrySet()) {
            Pair<Integer> range = entry.getValue();
            Integer featureIndex = entry.getKey();
            indexIdentifierMap.put(featureCounter, featureIndex);
            featureCounter++;
            indices.add(IntStream.range(range.getFirst(), range.getSecond())
                    .boxed()
                    .collect(Collectors.toList()));
        }
        // permute
        permutations = StreamPermutations.permutations(indices);
        permutationIterator = permutations.iterator();
    }

    public void nextVariationSet() {
        List<Integer> permuation = permutationIterator.next();
        for (int index = 0; index < permuation.size(); index++) {
            int featureIdentifier = indexIdentifierMap.get(index);
            Feature<?> feature = FeatureRegistry.get(featureIdentifier);
            feature.setAlternativeContent(permuation.get(index));
        }
        currentVariation++;
    }

    public boolean hasVariationsLeft() {
        return permutationIterator.hasNext();
    }

    public void logVariations() {
        for (Map.Entry<Integer, Pair<Integer>> entry : featureState.entrySet()) {
            int featureIdentifier = entry.getKey();
            System.out.println("  " + FeatureRegistry.get(featureIdentifier));
        }
    }

}
