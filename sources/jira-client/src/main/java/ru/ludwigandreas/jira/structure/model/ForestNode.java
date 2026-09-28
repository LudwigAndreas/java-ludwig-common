package ru.ludwigandreas.jira.structure.model;

import java.util.List;
import java.util.stream.Stream;

/**
 * A node of a decoded Structure forest: one row plus the rows nested under it.
 *
 * <p>Built by {@link ForestFormula#toTree(List)}. The children list is mutable during construction and
 * should be treated as read-only afterwards - it is exposed directly rather than copied so that building a
 * fifty-thousand-row tree does not copy every subtree once per level.
 *
 * @param row this node's row
 * @param children the rows nested directly under it, in order
 */
public record ForestNode(ForestRow row, List<ForestNode> children) {

    /** This node and every node beneath it, depth-first, in document order. */
    public Stream<ForestNode> flatten() {
        return Stream.concat(Stream.of(this), children.stream().flatMap(ForestNode::flatten));
    }

    /** Whether this node has no children. */
    public boolean isLeaf() {
        return children.isEmpty();
    }
}
