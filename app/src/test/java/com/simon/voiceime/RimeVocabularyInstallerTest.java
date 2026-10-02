package com.simon.voiceime;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;

public class RimeVocabularyInstallerTest {
    @Test public void numericTonesInstallExactMixedAndCharacterReadings() throws Exception {
        File user=Files.createTempDirectory("v650b-install").toFile();
        RimeVocabularyInstaller.install(user,Collections.singletonList(new String[]{"泊旻","ㄅㄛ2 ㄇㄧㄣ2"}));
        String table=new String(Files.readAllBytes(new File(user,"custom_phrase.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(table.contains("泊旻\t1i6aup6\t10000\n"));
        assertTrue(table.contains("泊旻\t1i6a\t10000\n"));
        assertTrue(table.contains("泊旻\t1a\t10000\n"));
        assertTrue(table.contains("泊\t1i6\t100\n"));
        assertFalse(table.contains("泊\t18")); // A different reading must not be guessed.
    }
    @Test public void incompleteAlignmentKeepsPhraseButCannotAssignCharacterReadings() throws Exception {
        File user=Files.createTempDirectory("v650b-alignment").toFile();
        RimeVocabularyInstaller.install(user,Collections.singletonList(new String[]{"泊旻","ㄅㄛ2"}));
        String table=new String(Files.readAllBytes(new File(user,"custom_phrase.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(table.contains("泊旻\t1i6\t10000\n"));
        assertFalse(table.contains("\n泊\t"));
        assertFalse(table.contains("\n旻\t"));
    }
    @Test public void refreshReplacesRemovedWordsAndNoopPreservesRevision() throws Exception {
        File user=Files.createTempDirectory("v650b-refresh").toFile();
        List<String[]> entries=Collections.singletonList(new String[]{"泊旻","ㄅㄛ2 ㄇㄧㄣ2"});
        RimeVocabularyInstaller.install(user,entries);long revision=RimeVocabularyInstaller.revision();
        RimeVocabularyInstaller.install(user,entries);assertEquals(revision,RimeVocabularyInstaller.revision());
        RimeVocabularyInstaller.install(user,Collections.emptyList());
        assertFalse(new String(Files.readAllBytes(new File(user,"custom_phrase.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8).contains("泊"));
    }
    @Test public void protectedFieldsDoNotCreateCommitHistory() throws Exception {
        File user=Files.createTempDirectory("v650b-protected").toFile();
        RimeVocabularyInstaller.setLearningEnabled(false);
        try { RimeVocabularyInstaller.remember(user,"泊旻","ㄅㄛˊ ㄇㄧㄣˊ"); }
        finally { RimeVocabularyInstaller.setLearningEnabled(true); }
        assertFalse(new File(user,"committed_vocab.tsv").exists());
    }
    @Test public void ordinaryCommitsAndTeachingRetainDistinctOriginsWithSameConversionTable() throws Exception {
        File user=Files.createTempDirectory("v660-origins").toFile();
        RimeVocabularyInstaller.rememberCommit(user,"明明魁","ㄇㄧㄥˊㄇㄧㄥˊㄎㄨㄟˊ");
        String automatic=readUtf8(new File(user,"custom_phrase.txt").toPath());
        assertEquals("\n",readUtf8(new File(user,"taught_vocab.tsv").toPath()));
        RimeVocabularyInstaller.remember(user,"明明魁","ㄇㄧㄥˊㄇㄧㄥˊㄎㄨㄟˊ");
        assertTrue(readUtf8(new File(user,"taught_vocab.tsv").toPath()).contains("明明魁\t"));
        assertEquals(automatic,readUtf8(new File(user,"custom_phrase.txt").toPath()));
        RimeVocabularyInstaller.rememberCommit(user,"明明魁","ㄇㄧㄥˊㄇㄧㄥˊㄎㄨㄟˊ");
        assertTrue(readUtf8(new File(user,"taught_vocab.tsv").toPath()).contains("明明魁\t"));
        RimeVocabularyInstaller.resetLearned(user);
        assertEquals("",readUtf8(new File(user,"taught_vocab.tsv").toPath()));
        assertEquals("",readUtf8(new File(user,"committed_vocab.tsv").toPath()));
    }
    private static String readUtf8(java.nio.file.Path path) throws Exception { return new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8); }
}
