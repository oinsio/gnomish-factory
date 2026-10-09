package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;

/**
 * Where a {@link DashboardWatch} reads its board from (design D10, D11 of
 * supervise-daemon-loops-and-embed-dashboard): the read-only tracker client and the two
 * configuration sections {@code BoardComposition.compose} needs beside it. The standalone {@code
 * gnomish dashboard} and the dashboard inside {@code serve} differ only in where this record comes
 * from, never in how the board is fetched.
 *
 * <p>Implements FR9, FR14 of supervise-daemon-loops-and-embed-dashboard.
 *
 * @param tracker the read-only tracker client the board is fetched from; never null
 * @param trackerConfig the project's validated {@code tracker:} section, supplying the WIP limit;
 *     never null
 * @param trackerProperties the factory-wide tracker settings, supplying the abort backoff; never
 *     null
 */
public record BoardSource(Tracker tracker, TrackerConfig trackerConfig, FactoryProperties.Tracker trackerProperties) {}
