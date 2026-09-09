package dev.capna.bardbot;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * This bot talks to Discord and nothing else.
 *
 * <p>That is the whole of its security posture, so it is enforced rather than promised. Profile and
 * crest images are URLs handed to Discord to fetch; the bot never retrieves one, which is why it
 * cannot be pointed at an internal address by anybody who can edit their own profile.
 */
class NoNetworkTest {

    @Test
    void nothingOpensASocket() {
        ArchRule rule = noClasses()
                .should().accessClassesThat().resideInAnyPackage(
                        "java.net.http..", "java.net..", "javax.net..")
                .because("BardBot talks to Discord and nothing else. If this ever needs to change, "
                        + "the change belongs in SECURITY.md in the same commit.");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.capna.bardbot"));
    }
}
