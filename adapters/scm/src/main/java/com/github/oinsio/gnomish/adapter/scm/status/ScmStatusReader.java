package com.github.oinsio.gnomish.adapter.scm.status;

import com.github.oinsio.gnomish.adapter.scm.ScmConnection;
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader;
import com.github.oinsio.gnomish.adapter.scm.ScmRepository;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.githubapi.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.githubapi.GithubHttpClient;
import com.github.oinsio.gnomish.githubapi.RateLimit;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the named verdicts the SCM holds for an attempt SHA (FR4, FR9, NFR-R2 of
 * migrate-verify-to-executor-contract, design D3): the combined commit status first — the call
 * GitHub and Gitea both serve and NFR-R2 prefers — then GitHub's check runs, which a platform with
 * no Checks API (Gitea) answers {@code 404} and which then contribute nothing. Both calls name the
 * SHA in their path and go through the shared conditional-request cache, so an unchanged listing
 * costs no rate-limit budget; the platform's own retry, inside {@link GithubHttpClient}, is the
 * only retry.
 *
 * <p>A rate-limit answer — as the core's one judgement, {@code GithubRateLimit}, read it onto the
 * conditional result (design D19) — is a {@link ScmStatusRead.RateLimited} with its pause; any other
 * answer that is not a
 * listing is a {@link ScmStatusReadException}. The guard's refusal, an exchange limit, a transport
 * failure and an interrupt leave as the core throws them. Nothing is held between reads but the
 * cache, so two instances reading one SHA reach the same verdicts.
 *
 * <p>Implements FR4, FR9, NFR-R2, NFR-S2 of migrate-verify-to-executor-contract.
 */
public final class ScmStatusReader {

    /** One page of either listing: the platform's ceiling. */
    private static final String PAGE = "?per_page=100";

    private static final int NOT_FOUND = 404;

    /** The check runs of a platform with no Checks API: none. */
    private static final String NO_CHECK_RUNS = "{\"check_runs\":[]}";

    private final GithubConditionalRequestCache cache;
    private final ScmRepository repository;

    /**
     * @param client the GitHub core over the admitted connection, carrying its token; never null
     * @param repository the repository the statuses are read from; never null
     */
    public ScmStatusReader(GithubHttpClient client, ScmRepository repository) {
        this.cache = new GithubConditionalRequestCache(client);
        this.repository = repository;
    }

    /**
     * Connects the {@code scm-status} built-in over the connection run assembly read and admitted
     * through the one connection reader (NFR-S2, design D3, D18): the token resolved by the
     * connection's credential name through the secrets port, the repository from the connection's
     * {@code repo}. No tracker subsection is read.
     *
     * @param connection the admitted {@code factory.check.scm-status} connection; never null
     * @param secrets the secrets port; never null
     * @return the reader over the admitted connection; never null
     * @throws IllegalArgumentException when the connection names no {@code owner/name}
     * @throws com.github.oinsio.gnomish.adapter.scm.ScmTokenException when the token does not resolve
     */
    public static ScmStatusReader connect(ScmConnection connection, SecretsProvider secrets) {
        ScmRepository repository =
                ScmRepository.of(ScmConnectionReader.PREFIX + ScmStatusExecutor.NAME, connection.settings());
        String token = connection.token(secrets, ScmStatusExecutor.TOKEN_ENV_VAR);
        return new ScmStatusReader(new GithubHttpClient(connection.endpoint(), token), repository);
    }

    /** The host of the endpoint this reader calls: the one host its describe declares. */
    String endpointHost() {
        return URI.create(cache.httpClient().apiUrl()).getHost();
    }

    /**
     * Reads the verdicts on {@code sha}.
     *
     * @param sha the attempt commit; never null
     * @return the verdicts, or the rate limit; never null
     * @throws ScmStatusReadException when a listing could not be read
     */
    ScmStatusRead read(AttemptSha sha) {
        String commit = repository.apiPath() + "/commits/" + sha.value();
        Listing statuses = get(commit + "/status" + PAGE, "combined status", false);
        if (statuses instanceof Listing.Limited limited) {
            return limited.read();
        }
        Listing checkRuns = get(commit + "/check-runs" + PAGE, "check runs", true);
        if (checkRuns instanceof Listing.Limited limited) {
            return limited.read();
        }
        List<ScmContext> reports = new ArrayList<>(ScmContextParser.statuses(((Listing.Body) statuses).text(), sha));
        reports.addAll(ScmContextParser.checkRuns(((Listing.Body) checkRuns).text(), sha));
        return new ScmStatusRead.Verdicts(ContextVerdicts.of(reports));
    }

    private Listing get(String path, String listing, boolean absentAllowed) {
        return switch (cache.get(cache.httpClient().newRequest(path), path)) {
            case GithubConditionalRequestCache.NotModified unchanged -> new Listing.Body(unchanged.previousBody());
            case GithubConditionalRequestCache.Fresh fresh -> classify(fresh, listing, absentAllowed);
        };
    }

    private static Listing classify(GithubConditionalRequestCache.Fresh fresh, String listing, boolean absentAllowed) {
        int status = fresh.statusCode();
        RateLimit limit = fresh.rateLimit();
        if (limit != null) {
            return new Listing.Limited(new ScmStatusRead.RateLimited(status, limit.pause()));
        }
        if (status == NOT_FOUND && absentAllowed) {
            return new Listing.Body(NO_CHECK_RUNS);
        }
        if (status / 100 != 2) {
            throw new ScmStatusReadException("the SCM answered the " + listing + " listing with HTTP " + status);
        }
        return new Listing.Body(fresh.body());
    }

    /** One listing's answer: its body, or a rate limit. */
    private sealed interface Listing {

        record Body(String text) implements Listing {}

        record Limited(ScmStatusRead.RateLimited read) implements Listing {}
    }
}
