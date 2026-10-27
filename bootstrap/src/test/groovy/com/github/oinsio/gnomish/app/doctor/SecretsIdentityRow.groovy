package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.IdentityRow.refusalOf
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerConfigException
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.TrackerWiring
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.tomakehurst.wiremock.WireMockServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.function.Function

/**
 * The identity row of the declared secrets (M1 of add-doctor-command): a law whose tracker is the
 * shipped GitHub adapter declares {@code GNOMISH_GITHUB_TOKEN}, and one real secrets adapter over
 * one factory home answers both sides. The startup side is the tracker resolution {@code take}
 * makes, which reads the token through the secrets port before its first request and refuses
 * without it; with the token it provisions against a WireMock repository. The doctor side
 * locates the same name through the same adapter.
 */
final class SecretsIdentityRow {

    private static final String TOKEN = GithubTrackerAdapterFactory.TOKEN_ENV_VAR

    private SecretsIdentityRow() {}

    static IdentityRow.Case secrets(Path dir, boolean present) {
        def github = new WireMockServer(options().dynamicPort())
        github.start()
        try {
            github.stubFor(get(urlPathEqualTo('/repos/acme/widgets/labels')).willReturn(aResponse().withStatus(200).withBody('[]')))
            github.stubFor(post(urlPathEqualTo('/repos/acme/widgets/labels')).willReturn(aResponse().withStatus(201).withBody('{}')))
            return judged(dir.resolve(present ? 'present' : 'absent'), present, github.baseUrl())
        } finally {
            github.stop()
        }
    }

    private static IdentityRow.Case judged(Path dir, boolean present, String apiUrl) {
        def home = FactoryHome.at(Files.createDirectories(dir.resolve('home')))
        if (present) {
            Path file = Files.createDirectories(home.hostSecret(TOKEN).parent).resolve(TOKEN)
            Files.writeString(file, 'tok-identity\n')
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString('rw-------'))
        }
        // The adapter's own search with no process environment behind it, so only the home answers.
        def secrets = new EnvFileSecretsProvider(home, null, { String name ->
            null
        } as Function<String, String>)
        def registry = [github: new GithubTrackerAdapterFactory()]
        def config = new TrackerConfig('github', 3, ['api-url': apiUrl, repo: 'acme/widgets'])
        def base = DoctorSpecSupport.definition()
        def definition = new PipelineDefinition(base.schemaVersion(), base.defaultLimits(), base.stages(), config)

        def wiring = new TrackerWiring(registry, secrets, TrackerValidatorStub.plainSource(), VirtualTimeEquipment.create())
        def refusal = refusalOf(GithubTrackerConfigException) {
            wiring.resolveTracker(wiring.resolveFactory(config), config, InstanceId.generate('identity'),
            TaskGitFixture.real().epochs())
        }

        def readings = DoctorSpecSupport.readings([:], [secretsPort: secrets, registry: registry])
        def doctor = new SecretsDoctorCheck(readings, secrets).run(DoctorSpecSupport.bound(false, true, definition))
        assert !doctor*.detail().join('\n').contains('tok-identity')
        IdentityRow.Case.refusing(present ? 'the token in the host secrets folder' : 'no token anywhere', refusal, doctor,
                refusal == null ? [] : [TOKEN])
    }
}
