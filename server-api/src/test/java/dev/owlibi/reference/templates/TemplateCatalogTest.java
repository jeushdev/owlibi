
package dev.owlibi.reference.templates;

import dev.owlibi.reference.web.ApiFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemplateCatalogTest {

    private final TemplateCatalog catalog = new TemplateCatalog();

    @Test
    void loadsTemplatesInExpectedOrder() {
        var templates = catalog.list();

        assertEquals(5, templates.size());
        assertEquals(
            "blank",
            templates.get(0).id()
        );
        assertEquals(
            "coding",
            templates.get(1).id()
        );
        assertEquals(
            "written",
            templates.get(2).id()
        );
        assertEquals(
            "reporting-presentation",
            templates.get(3).id()
        );
        assertEquals(
            "filming-video",
            templates.get(4).id()
        );
    }

    @Test
    void readsCurrentTemplate() {
        var template = catalog.read("coding", null);

        assertEquals("coding", template.id());
        assertEquals(1, template.version());
    }

    @Test
    void readsSpecificTemplateVersion() {
        var template = catalog.read("blank", 2);

        assertEquals("blank", template.id());
        assertEquals(2, template.version());
    }

    @Test
    void usesBlankTemplateByDefault() {
        var template = catalog.forCreation(null);

        assertEquals("blank", template.id());
        assertEquals(2, template.version());
    }

    @Test
    void acceptsAvailableTemplateForCreation() {
        var template = catalog.forCreation(
            new TemplateCatalog.Selection("coding", 1)
        );

        assertEquals("coding", template.id());
    }

    @Test
    void rejectsUnavailableTemplate() {
        ApiFailure exception = assertThrows(
            ApiFailure.class,
            () -> catalog.read("unknown", null)
        );

        assertEquals(404, exception.status());
    }

    @Test
    void rejectsOutdatedTemplateVersionForCreation() {
        ApiFailure exception = assertThrows(
            ApiFailure.class,
            () -> catalog.forCreation(
                new TemplateCatalog.Selection("blank", 1)
            )
        );

        assertEquals(409, exception.status());
    }
}
