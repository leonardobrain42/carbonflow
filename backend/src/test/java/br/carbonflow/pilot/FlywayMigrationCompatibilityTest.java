package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Protect already-released Flyway history against accidental mutation.
 * Old SANDBOX databases ran the original V69, checksum -1778044537.
 * The submitted PDF filename evolves additively under V69.1, never by rewriting V69.
 */
@QuarkusTest
class FlywayMigrationCompatibilityTest {
    @Inject PilotDatabase database;
    private static final int PUBLISHED_V69_CHECKSUM = -1778044537;

    @Test
    void immutablePublishedV69MatchesExistingSandboxHistory() throws Exception {
        var stream=getClass().getClassLoader().getResourceAsStream(
            "db/migration/V69__open_asset_evidence_links.sql");
        assertNotNull(stream,"A V69 original não pode ser removida.");
        var crc=new CRC32();
        try(var lines=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            String line;
            while((line=lines.readLine())!=null)
                crc.update(line.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(PUBLISHED_V69_CHECKSUM,(int)crc.getValue(),
            "NÃO edite a V69 publicada: crie uma migração nova e aditiva.");
        Integer applied=database.read(c->first(c,"""
            SELECT checksum FROM flyway_schema_history WHERE version='69' AND success=true
            """,r->r.getInt(1)));
        assertEquals(PUBLISHED_V69_CHECKSUM,applied);
    }

    @Test
    void filenameBackfillIsAnAdditiveMigrationAndResultIsConstrained() {
        Integer applied=database.read(c->first(c,"""
            SELECT count(*) FROM flyway_schema_history
            WHERE version='69.1' AND success=true
            """,r->r.getInt(1)));
        assertEquals(1,applied,"V69.1 deve ser aplicada sem alterar V69 ou V70.");
        String dataType=database.read(c->first(c,"""
            SELECT data_type FROM information_schema.columns
            WHERE table_schema='public' AND table_name='app_open_asset_evidence'
              AND column_name='submitted_file_name' AND is_nullable='NO'
            """,r->r.getString(1)));
        assertEquals("text",dataType);
        Integer validated=database.read(c->first(c,"""
            SELECT count(*) FROM pg_constraint
            WHERE conrelid='app_open_asset_evidence'::regclass
              AND conname='app_open_asset_evidence_submitted_file_name_check'
              AND convalidated
            """,r->r.getInt(1)));
        assertEquals(1,validated);
        String triggerState=database.read(c->first(c,"""
            SELECT tgenabled::text FROM pg_trigger
            WHERE tgrelid='app_open_asset_evidence'::regclass
              AND tgname='app_open_asset_evidence_immutable'
            """,r->r.getString(1)));
        assertEquals("O",triggerState,
            "O gatilho de imutabilidade deve ser reativado após o backfill V69.1.");
    }
}
