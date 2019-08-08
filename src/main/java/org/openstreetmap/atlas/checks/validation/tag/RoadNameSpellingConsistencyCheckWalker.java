package org.openstreetmap.atlas.checks.validation.tag;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.openstreetmap.atlas.geography.atlas.items.Edge;
import org.openstreetmap.atlas.geography.atlas.walker.EdgeWalker;
import org.openstreetmap.atlas.tags.names.NameTag;
import org.openstreetmap.atlas.utilities.scalars.Distance;

/**
 * A RoadNameSpellingConsistencyCheckWalker can be used to collect all edges that have NameTag
 * values that are at an edit distance of 1 from the starting edge's {@link NameTag} value. Collected edges
 * are within some linear search area (configurable).
 *
 * @author seancoulter
 */
class RoadNameSpellingConsistencyCheckWalker extends EdgeWalker
{

    /**
     * Holds the unicode representation of CJK (Chinese, Japanese, Korean) Unified Ideograph numbers
     * (e.g. 四) These numbers are not contained in {Nd} so we create separate storage for them
     */
    enum CJKNumbers
    {
        CJK_ZERO(0x3007),
        CJK_ONE(0x4E00),
        CJK_TWO(0x4E8C),
        CJK_THREE(0x4E09),
        CJK_FOUR(0x56DB),
        CJK_FIVE(0x4E94),
        CJK_SIX(0x516D),
        CJK_SEVEN(0x4E03),
        CJK_EIGHT(0x516B),
        CJK_NINE(0x4E5D),
        CJK_TEN(0x5341),
        CJK_ELEVEN(0x5EFF),
        CJK_TWELVE(0x5345);

        private final int value;

        CJKNumbers(final int value)
        {
            this.value = value;
        }

        public int getValue()
        {
            return this.value;
        }
    }

    // Matches identifiers sometimes found in road names. E.g. the 'A' in Road A, the "12c" in 12c
    // Street, and the "Y6" in Y6 Drive.
    // Identifiers are defined to be any space-delimited string that either contains at least one
    // digit OR contains a single character which may be preceded or followed by a single
    // punctuation character.
    // We consider names with different identifiers to be from different roads, and as such we don't
    // flag for spelling inconsistencies between identifiers.
    private static final String ALPHANUMERIC_IDENTIFIER_STRING_REGEX = ".*\\p{Nd}+.*";
    private static final String CHARACTER_IDENTIFIER_STRING_REGEX = "\\p{P}.\\p{P}|.\\p{P}|\\p{P}.|^.$";

    private static final String WHITESPACE_REGEX = "\\s+";

    /**
     * Evaluate the {@link NameTag}s of the startingEdge and an incomingEdge to see if their
     * spellings are inconsistent with one another.
     *
     * @param startEdge
     *            the edge from which the search started
     * @return true if incomingEdge's name exists and is at an edit distance of 1 from the start edge's name; false otherwise
     */
    @SuppressWarnings("squid:S3655")
    static Predicate<Edge> isEdgeWithInconsistentSpelling(final Edge startEdge)
    {
        return incomingEdge ->
        {
            if (!incomingEdge.getName().isPresent())
            {
                return false;
            }
            // NOSONAR because we've filtered out startEdges without names
            final String startEdgeName = startEdge.getName().get();
            final String incomingEdgeName = incomingEdge.getName().get();
            return checkBeforeEditDistance(incomingEdgeName, startEdgeName);
        };
    }

    /**
     * Wrapper for editDistanceIsOne(). Handles cases where the incomingEdge has the same name as
     * the startingEdge and where the edit distance between the two names is greater than one before
     * analyzing the edit distance.
     *
     * @param incomingEdgeName
     *            the next edge in the search area
     * @param startEdgeName
     *            the edge from which the search started
     * @return true if the edit distance between two edge's names is 1; false otherwise
     */
    private static boolean checkBeforeEditDistance(final String incomingEdgeName,
            final String startEdgeName)
    {
        if (Math.abs(incomingEdgeName.length() - startEdgeName.length()) > 1)
        {
            return false;
        }
        if (incomingEdgeName.equals(startEdgeName))
        {
            return false;
        }
        return editDistanceIsOne(incomingEdgeName, startEdgeName);
    }

    /**
     * The function used to collect {@link Edge}s that fall within the search area.
     *
     * @param startEdge
     *            the edge from which the search started
     * @param maximumSearchDistance
     *            the maximum distance from the end of the incoming edge to the start of the
     *            starting edge
     * @return A stream of edges that fall in the search area
     */
    private static Function<Edge, Stream<Edge>> edgesWithinMaximumSearchDistance(
            final Edge startEdge, final Distance maximumSearchDistance)
    {
        return incomingEdge -> incomingEdge.end().getLocation()
                .distanceTo(startEdge.start().getLocation())
                .isLessThanOrEqualTo(maximumSearchDistance)
                        ? incomingEdge.connectedEdges().stream().filter(Edge::isMasterEdge)
                        : Stream.empty();
    }

    /**
     * Compute the edit distance between two road names. Should only take in Strings that are the
     * same length, or whose lengths are off by a single character.
     *
     * @param incomingEdgeName
     *            the name of the next edge in the search area
     * @param startingEdgeName
     *            the name of the edge from which the search started
     * @return true if the edit distance between two edge's names is 1; false otherwise
     */
    private static boolean editDistanceIsOne(final String incomingEdgeName,
            final String startingEdgeName)
    {
        final List<String> incomingEdgeNameAlphanumericIdentifierStrings = Arrays
                .stream(incomingEdgeName.split(WHITESPACE_REGEX))
                .filter(substring -> substring.matches(ALPHANUMERIC_IDENTIFIER_STRING_REGEX)
                        || substring.matches(CHARACTER_IDENTIFIER_STRING_REGEX)
                        || Stream.of(CJKNumbers.values())
                                .anyMatch(cjkNumber -> substring.contains(
                                        new String(Character.toChars(cjkNumber.getValue())))))
                .collect(Collectors.toList());
        final List<String> startingEdgeNameAlphanumericIdentifierStrings = Arrays
                .stream(startingEdgeName.split(WHITESPACE_REGEX))
                .filter(substring -> substring.matches(ALPHANUMERIC_IDENTIFIER_STRING_REGEX)
                        || substring.matches(CHARACTER_IDENTIFIER_STRING_REGEX)
                        || Stream.of(CJKNumbers.values())
                                .anyMatch(cjkNumber -> substring.contains(
                                        new String(Character.toChars(cjkNumber.getValue())))))
                .collect(Collectors.toList());

        // If the two street names have different alphanumeric identifier strings anywhere in their
        // names, they're classified as being from different roads.
        final long incomingEdgeNameIdentifierCount = incomingEdgeNameAlphanumericIdentifierStrings
                .size();
        final long startingEdgeNameIdentifierCount = startingEdgeNameAlphanumericIdentifierStrings
                .size();
        final long combinedIdentifierCount = Stream
                .concat(incomingEdgeNameAlphanumericIdentifierStrings.stream(),
                        startingEdgeNameAlphanumericIdentifierStrings.stream())
                .distinct().count();
        if (combinedIdentifierCount > incomingEdgeNameIdentifierCount
                || combinedIdentifierCount > startingEdgeNameIdentifierCount)
        {
            return false;
        }

        // We now know that the street names have the same identifiers or no identifiers at all.
        // Check edit distance as usual.
        int editDistance = 0;
        // Check edit distance between 2 different strings of the same length
        if (incomingEdgeName.length() == startingEdgeName.length())
        {
            for (int index = 0; index < incomingEdgeName.length(); ++index)
            {
                if (incomingEdgeName.charAt(index) != startingEdgeName.charAt(index))
                {
                    // If > 1 substitutions are required for the strings to match, then they're not
                    // edit distance of 1
                    if (++editDistance > 1)
                    {
                        return false;
                    }
                }
            }
        }

        // Check edit distance between 2 different strings with lengths off by one
        else
        {
            final boolean incomingEdgeNameIsLonger = incomingEdgeName.length() > startingEdgeName
                    .length();
            final int endIndex = incomingEdgeNameIsLonger ? startingEdgeName.length()
                    : incomingEdgeName.length();
            int incomingEdgePointer = 0;
            int startingEdgePointer = 0;
            while (startingEdgePointer < endIndex)
            {
                if (incomingEdgeName.charAt(incomingEdgePointer) != startingEdgeName
                        .charAt(startingEdgePointer))
                {
                    if (++editDistance > 1)
                    {
                        return false;
                    }
                    if (incomingEdgeNameIsLonger)
                    {
                        ++incomingEdgePointer;
                    }
                    else
                    {
                        ++startingEdgePointer;
                    }
                    if (incomingEdgeName.charAt(incomingEdgePointer) != startingEdgeName
                            .charAt(startingEdgePointer))
                    {
                        return false;
                    }
                }
                ++incomingEdgePointer;
                ++startingEdgePointer;
            }
        }
        // The road names are at an edit distance of one, also meaning their spellings are
        // inconsistent
        return true;
    }

    /**
     * Walker for {@link RoadNameSpellingConsistencyCheck}.
     *
     * @param startEdge
     *            the edge from which the search started
     * @param maximumSearchDistance
     *            the maximum distance from the end of the incoming edge to the start of the
     *            starting edge
     */
    RoadNameSpellingConsistencyCheckWalker(final Edge startEdge,
            final Distance maximumSearchDistance)
    {
        super(startEdge, edgesWithinMaximumSearchDistance(startEdge, maximumSearchDistance));
    }

}
