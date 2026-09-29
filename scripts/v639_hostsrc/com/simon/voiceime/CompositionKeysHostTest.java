package com.simon.voiceime;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Exercises composition-editing keys through the production controller and JNI boundary. */
public final class CompositionKeysHostTest {
  static final class Engine implements ZhuyinInputController.Engine, AutoCloseable {
    private final RimeZhuyinNative rime;
    Engine(String shared, String user) { rime = new RimeZhuyinNative(shared, user); }
    public void key(String symbol) { rime.key(ZhuyinKeyMap.physicalKey(symbol)); }
    public void backspace() { rime.backspace(); }
    public void space() { rime.space(); }
    public void enter() { rime.enter(); }
    public void choose(int index) { rime.choose(index); }
    public void moveCursor(String direction) { rime.moveCursor("right".equals(direction)); }
    public int cursorPosition() { return rime.cursor(); }
    public String composingText() { return rime.composing(); }
    public List<String> candidates() { return Arrays.asList(rime.candidates()); }
    public String takeCommit() { return rime.takeCommit(); }
    public void clear() { rime.clear(); }
    public void close() { rime.close(); }
  }

  private static final String[] THREE_SYMBOLS = {"ㄨ", "ㄛ", "ㄒ"};

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("expected <shared> <user>");
    int passed = 0;
    List<String> failures = new ArrayList<>();
    passed += run("backspace", () -> backspace(args), failures);
    passed += run("enter", () -> enterWithComposition(args), failures);
    passed += run("empty-enter", () -> emptyEnter(args), failures);
    passed += run("clear", () -> clear(args), failures);
    passed += run("space", () -> space(args), failures);
    if (!failures.isEmpty()) throw new AssertionError("failed rows=" + failures);
    System.out.println("PASS composition key table rows=" + passed + "/5");
  }

  private interface Row { boolean run() throws Exception; }
  private static int run(String name, Row row, List<String> failures) throws Exception {
    try { return row.run() ? 1 : 0; }
    catch (AssertionError error) { failures.add(name + ": " + error.getMessage()); System.out.println("FAIL ROW " + name + ": " + error.getMessage()); return 0; }
  }

  private static boolean backspace(String[] args) throws Exception {
    try (Engine engine = new Engine(args[0], args[1])) {
      ZhuyinInputController controller = new ZhuyinInputController(engine);
      ZhuyinInputController.State before = type(controller, THREE_SYMBOLS);
      int length = zhuyinSymbols(before.composingText);
      check(length == THREE_SYMBOLS.length, "backspace setup preedit=" + quote(before.composingText));
      for (int expected = THREE_SYMBOLS.length - 1; expected >= 0; expected--) {
        ZhuyinInputController.State after = controller.press("backspace");
        check(zhuyinSymbols(after.composingText) == expected,
            "backspace expected symbol count=" + expected + " actual=" + zhuyinSymbols(after.composingText)
                + " preedit=" + quote(after.composingText));
        check(after.commitText.isEmpty(), "backspace committed=" + quote(after.commitText));
      }
      System.out.println("ROW backspace preedit=" + quote(before.composingText) + " -> empty; commit=empty");
      return true;
    }
  }

  private static boolean enterWithComposition(String[] args) throws Exception {
    try (Engine engine = new Engine(args[0], args[1])) {
      ZhuyinInputController controller = new ZhuyinInputController(engine);
      ZhuyinInputController.State before = type(controller, "ㄨ", "ㄛ");
      check(!before.composingText.isEmpty(), "enter setup composition empty");
      String shown = firstCandidate(before);
      check(!shown.isEmpty(), "enter setup has no preview candidate preedit=" + quote(before.composingText));
      ZhuyinInputController.State after = controller.press("enter");
      check(after.composingText.isEmpty(), "enter left composition=" + quote(after.composingText));
      check(after.commitText.equals(shown),
          "enter expected preview candidate=" + quote(shown) + " commit=" + quote(after.commitText));
      System.out.println("ROW enter composition preview=" + quote(shown) + " commit=" + quote(after.commitText));
      return true;
    }
  }

  private static boolean emptyEnter(String[] args) throws Exception {
    try (Engine engine = new Engine(args[0], args[1])) {
      ZhuyinInputController.State after = new ZhuyinInputController(engine).press("enter");
      check(after.composingText.isEmpty(), "empty enter composition=" + quote(after.composingText));
      check(after.commitText.isEmpty(), "empty enter unexpectedly committed=" + quote(after.commitText));
      System.out.println("ROW empty-enter composition=empty commit=empty");
      return true;
    }
  }

  private static boolean clear(String[] args) throws Exception {
    try (Engine engine = new Engine(args[0], args[1])) {
      ZhuyinInputController controller = new ZhuyinInputController(engine);
      type(controller, "ㄨ", "ㄛ");
      ZhuyinInputController.State after = controller.clear();
      check(after.composingText.isEmpty(), "clear left composition=" + quote(after.composingText));
      check(after.commitText.isEmpty(), "clear committed=" + quote(after.commitText));
      System.out.println("ROW clear composition=empty commit=empty");
      return true;
    }
  }

  private static boolean space(String[] args) throws Exception {
    try (Engine engine = new Engine(args[0], args[1])) {
      ZhuyinInputController controller = new ZhuyinInputController(engine);
      ZhuyinInputController.State before = type(controller, "ㄨ", "ㄛ");
      ZhuyinInputController.State after = controller.press("space");
      check(after.composingText.equals(before.composingText + "ˉ"),
          "space expected first-tone composition=" + quote(before.composingText + "ˉ")
              + " actual=" + quote(after.composingText));
      check(after.commitText.isEmpty(), "space committed=" + quote(after.commitText));
      System.out.println("ROW space composition=" + quote(after.composingText) + " commit=empty");
      return true;
    }
  }

  private static ZhuyinInputController.State type(ZhuyinInputController controller, String... keys) {
    ZhuyinInputController.State state = controller.state();
    for (String key : keys) state = controller.press(key);
    return state;
  }
  private static String firstCandidate(ZhuyinInputController.State state) {
    return state.candidates.isEmpty() ? "" : state.candidates.get(0);
  }
  private static int zhuyinSymbols(String value) {
    int count = 0;
    for (int i = 0; i < value.length();) {
      int cp = value.codePointAt(i);
      if ("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙".indexOf(cp) >= 0) count++;
      i += Character.charCount(cp);
    }
    return count;
  }
  private static String quote(String value) { return "[" + value + "]"; }
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
