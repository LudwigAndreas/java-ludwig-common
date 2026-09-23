package ru.ludwigandreas.jira.api;

import java.util.List;
import java.util.stream.Stream;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.jql.JqlQuery;
import ru.ludwigandreas.jira.model.issue.Issue;
import ru.ludwigandreas.jira.model.search.SearchResult;
import ru.ludwigandreas.jira.page.Page;
import ru.ludwigandreas.jira.page.PageRequest;
import ru.ludwigandreas.jira.page.Pages;
import ru.ludwigandreas.jira.request.SearchRequest;

/**
 * JQL search.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#search()}.
 *
 * <p>Issued as a {@code POST} and marked retryable, because it is a read whose payload does not fit in a
 * URL - see {@link SearchRequest}. That is the one place in this client where a {@code POST} opts back into
 * the retry policy.
 */
public final class SearchApi {

    private static final String SEARCH = ApiPaths.API_2 + "/search";

    private final JiraRestClient rest;

    public SearchApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /**
     * Runs one page of a search.
     *
     * @param request the query and window
     * @return the matching page, with Jira's total count
     */
    public SearchResult search(SearchRequest request) {
        return rest.post(SEARCH)
                .operation("search")
                .retryable(true)
                .body(request)
                .as(SearchResult.class);
    }

    /** Runs the first page of a query at the default page size. */
    public SearchResult search(JqlQuery query) {
        return search(SearchRequest.of(query).build());
    }

    /**
     * Streams every matching issue, fetching pages as the stream is consumed.
     *
     * <p>Lazy: a {@code findFirst} on a query matching a hundred thousand issues makes one request. The
     * stream stops issuing requests as soon as it is abandoned, so it is safe inside a {@code limit}.
     *
     * <p>Order the query by something stable before using this on a large result set - see
     * {@link ru.ludwigandreas.jira.jql.JqlQuery.Builder#orderByStableWalk()}. Jira pages by offset, so a
     * result set that reorders mid-walk loses rows.
     *
     * @param request the query; its window is replaced page by page
     * @return a lazy stream over every matching issue
     */
    public Stream<Issue> searchAll(SearchRequest request) {
        int pageSize = request.maxResults() == null ? PageRequest.DEFAULT_PAGE_SIZE : request.maxResults();
        return Pages.stream(window -> pageFor(request, window), pageSize);
    }

    /** Streams every matching issue of a query, at the default page size. */
    public Stream<Issue> searchAll(JqlQuery query) {
        return searchAll(SearchRequest.of(query).build());
    }

    /**
     * Streams every matching issue a page at a time, for callers that process batches - a bulk write, a
     * database flush - rather than single issues.
     *
     * @param request the query
     * @return a lazy stream over the pages
     */
    public Stream<Page<Issue>> searchAllPages(SearchRequest request) {
        int pageSize = request.maxResults() == null ? PageRequest.DEFAULT_PAGE_SIZE : request.maxResults();
        return Pages.streamPages(window -> pageFor(request, window), pageSize);
    }

    /**
     * How many issues a query matches, without fetching any of them.
     *
     * <p>Asks for a single row and reads the total off the response, which is the cheapest count Jira
     * offers - there is no count endpoint on Server 9.12.
     *
     * @param query the query
     * @return the number of matching issues visible to the calling user
     */
    public int count(JqlQuery query) {
        SearchResult result = search(SearchRequest.of(query).maxResults(1).fields(List.of("id")).build());
        return Math.max(result.total(), 0);
    }

    private Page<Issue> pageFor(SearchRequest request, PageRequest window) {
        return search(request.withWindow(window)).toPage();
    }
}
