package dev.capna.bardbot;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * This bot talks to Discord, and fetches a picture from exactly one class.
 *
 * <p>That is the whole of its security posture, so it is enforced rather than promised. Profile and
 * crest images are URLs handed to Discord to fetch; the bot never retrieves one anywhere else,
 * which is why it cannot be pointed at an internal address by anybody who can edit their own
 * profile.
 *
 * <p>{@code Avatars} is the single exception, and it is one because the Path card has to draw a
 * Bard's face into an image before uploading it, which Discord cannot do on the bot's behalf. The
 * rules that keep that one fetch safe are in {@code AvatarsTest}; this test only keeps the
 * exception from quietly becoming two.
 */
class NoNetworkTest {

    private static final String FETCHER = "dev.capna.bardbot.path.Avatars";

    @Test
    void nothingElseOpensASocket() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(FETCHER)
                .should().accessClassesThat().resideInAnyPackage(
                        "java.net.http..", "java.net..", "javax.net..")
                .because("BardBot talks to Discord, and fetches pictures only in " + FETCHER
                        + ", which allows https, a short list of image hosts, no redirects and "
                        + "no private addresses. A second class doing its own fetching is how "
                        + "that becomes untrue. If this ever needs to change, the change belongs "
                        + "in SECURITY.md in the same commit.");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.capna.bardbot"));
    }
}
