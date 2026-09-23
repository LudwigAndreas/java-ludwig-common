package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.jira.jql.JqlQuery;
import ru.ludwigandreas.jira.page.PageRequest;

/**
 * A JQL search: the query, the window, and which parts of each issue to bring back.
 *
 * <p>The two options that decide whether a search is fast or unusable:
 *
 * <p><b>{@code fields}.</b> Asking for nothing means Jira returns the full navigable field set for every
 * issue, which on an instance with 300 custom fields is tens of kilobytes per issue for data the caller
 * throws away. Name the fields. {@link Builder#fields(String...)} is the single highest-leverage call in
 * this class.
 *
 * <p><b>{@code expand}.</b> {@code changelog} in particular multiplies the response size by the length of
 * each issue's history, and {@code renderedFields} makes Jira run the wiki renderer over every text field
 * of every result.
 *
 * <p>Sent as a {@code POST} to {@code /rest/api/2/search} rather than a {@code GET}: a JQL query plus a
 * field list routinely exceeds what a reverse proxy will accept in a URL, and the failure when it does is a
 * 414 from the proxy rather than anything Jira reports. The request is still marked retryable, because it
 * is a read despite the method.
 */
public final class SearchRequest {

    private final String jql;
    private final Integer startAt;
    private final Integer maxResults;
    private final List<String> fields;
    private final List<String> expand;
    private final Boolean validateQuery;
    private final List<String> properties;
    private final Boolean fieldsByKeys;

    private SearchRequest(Builder builder) {
        this.jql = builder.jql;
        this.startAt = builder.startAt;
        this.maxResults = builder.maxResults;
        this.fields = builder.fields.isEmpty() ? null : List.copyOf(builder.fields);
        this.expand = builder.expand.isEmpty() ? null : List.copyOf(builder.expand);
        this.validateQuery = builder.validateQuery;
        this.properties = builder.properties.isEmpty() ? null : List.copyOf(builder.properties);
        this.fieldsByKeys = builder.fieldsByKeys;
    }

    /** A builder for a search over a built query. */
    public static Builder of(JqlQuery query) {
        return new Builder(query.render());
    }

    /** A builder for a search over a hand-written JQL string. */
    public static Builder of(String jql) {
        return new Builder(jql);
    }

    /** The JQL text. */
    @JsonProperty("jql")
    public String jql() {
        return jql;
    }

    /** Zero-based index of the first issue to return. */
    @JsonProperty("startAt")
    public Integer startAt() {
        return startAt;
    }

    /** Page size. */
    @JsonProperty("maxResults")
    public Integer maxResults() {
        return maxResults;
    }

    /** Field ids to return; {@code null} means Jira's default navigable set. */
    @JsonProperty("fields")
    public List<String> fields() {
        return fields;
    }

    /** Expansions to apply. */
    @JsonProperty("expand")
    public List<String> expand() {
        return expand;
    }

    /** Whether Jira should validate the query and report warnings. */
    @JsonProperty("validateQuery")
    public Boolean validateQuery() {
        return validateQuery;
    }

    /** Entity property keys to return with each issue. */
    @JsonProperty("properties")
    public List<String> properties() {
        return properties;
    }

    /** Whether {@code fields} names fields by key rather than by id. */
    @JsonProperty("fieldsByKeys")
    public Boolean fieldsByKeys() {
        return fieldsByKeys;
    }

    /** This request with a different window, used by the paging walk. */
    public SearchRequest withWindow(PageRequest window) {
        Builder builder = new Builder(jql);
        builder.startAt = window.startAt();
        builder.maxResults = window.maxResults();
        if (fields != null) {
            builder.fields.addAll(fields);
        }
        if (expand != null) {
            builder.expand.addAll(expand);
        }
        if (properties != null) {
            builder.properties.addAll(properties);
        }
        builder.validateQuery = validateQuery;
        builder.fieldsByKeys = fieldsByKeys;
        return builder.build();
    }

    /** Fluent builder for {@link SearchRequest}. */
    public static final class Builder {

        private final String jql;
        private final List<String> fields = new ArrayList<>();
        private final List<String> expand = new ArrayList<>();
        private final List<String> properties = new ArrayList<>();
        private Integer startAt;
        private Integer maxResults;
        private Boolean validateQuery;
        private Boolean fieldsByKeys;

        private Builder(String jql) {
            this.jql = jql;
        }

        /** Sets the window explicitly. */
        public Builder window(PageRequest window) {
            this.startAt = window.startAt();
            this.maxResults = window.maxResults();
            return this;
        }

        /** Sets the first row index. */
        public Builder startAt(int startAt) {
            this.startAt = startAt;
            return this;
        }

        /** Sets the page size. */
        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        /** Names the fields to return. {@code "*navigable"} and {@code "*all"} are accepted by Jira too. */
        public Builder fields(String... fieldIds) {
            this.fields.addAll(Arrays.asList(fieldIds));
            return this;
        }

        /** Names the fields to return. */
        public Builder fields(Collection<String> fieldIds) {
            this.fields.addAll(fieldIds);
            return this;
        }

        /** Adds expansions, for example {@code names}, {@code schema}, {@code changelog}, {@code renderedFields}. */
        public Builder expand(String... expansions) {
            this.expand.addAll(Arrays.asList(expansions));
            return this;
        }

        /**
         * Asks Jira for the {@code names} and {@code schema} expansions, which is what
         * {@link ru.ludwigandreas.jira.field.FieldAccess} needs to decode custom fields without a second
         * call to {@code /field}.
         *
         * @return this builder
         */
        public Builder withFieldMetadata() {
            return expand("names", "schema");
        }

        /** Asks for entity properties by key. */
        public Builder properties(String... keys) {
            this.properties.addAll(Arrays.asList(keys));
            return this;
        }

        /** Turns query validation on or off; off makes Jira skip the total count as well. */
        public Builder validateQuery(boolean validateQuery) {
            this.validateQuery = validateQuery;
            return this;
        }

        /** Interprets {@code fields} as field keys rather than field ids. */
        public Builder fieldsByKeys(boolean fieldsByKeys) {
            this.fieldsByKeys = fieldsByKeys;
            return this;
        }

        /** Builds the immutable request. */
        public SearchRequest build() {
            return new SearchRequest(this);
        }
    }
}
