package dev.smithyai.orchestrator.service.docker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class KubernetesNamesTest {

    @Test
    void validShortNamesStayAsTheyAre() {
        assertEquals("smithy-owner-repo-12", KubernetesNames.objectName("smithy-owner-repo-12"));
    }

    @Test
    void invalidCharactersAreReplacedAndHashed() {
        String name = KubernetesNames.objectName("Smithy_group.sub_repo-7");

        assertTrue(name.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?"), name);
        assertTrue(name.startsWith("smithy-group-sub-repo-7-"), name);
        assertNotEquals(
            name,
            KubernetesNames.objectName("smithy-group-sub-repo-7"),
            "a rewritten name must not collide with the name it was rewritten to"
        );
    }

    @Test
    void longNamesAreCutToFortyCharactersWithAStableHash() {
        String original = "architect-learn-some-very-long-group/nested/subgroup/repository-name-1234";

        String name = KubernetesNames.objectName(original);

        assertTrue(name.length() <= KubernetesNames.MAX_LENGTH, name);
        assertTrue(name.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?"), name);
        assertEquals(name, KubernetesNames.objectName(original));
        assertNotEquals(name, KubernetesNames.objectName(original + "5"));
    }

    @Test
    void namesWithoutUsableCharactersStillGetAName() {
        assertTrue(KubernetesNames.objectName("___").matches("t[0-9a-f]{8}"));
    }

    @Test
    void labelValuesAreTrimmedToWhatKubernetesAccepts() {
        assertEquals("smithy", KubernetesNames.labelValue("smithy"));
        assertEquals("a-b.c_d", KubernetesNames.labelValue("-a/b.c_d."));
        assertEquals(63, KubernetesNames.labelValue("x".repeat(80)).length());
    }
}
