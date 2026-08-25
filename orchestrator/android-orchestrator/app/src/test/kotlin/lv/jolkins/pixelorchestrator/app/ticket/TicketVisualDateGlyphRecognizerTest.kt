package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class TicketVisualDateGlyphRecognizerTest {
  @Test
  fun recognizesSameDateRangeInLightAndDarkThemesWithoutExposingDate() {
    val light = render("2408202625082026", brightGlyph = false)
    val dark = render("2408202625082026", brightGlyph = true)
    val lightRanges = TicketVisualDateGlyphRecognizer.recognize(light, 96, 7)
    val darkRanges = TicketVisualDateGlyphRecognizer.recognize(dark, 96, 7)

    assertEquals(1, lightRanges.size)
    assertEquals(1, darkRanges.size)
    assertEquals(lightRanges.single().anchor, darkRanges.single().anchor)
    assertEquals(24, lightRanges.single().anchor.length)
    assertFalse(lightRanges.single().anchor.contains("2026"))
  }

  @Test
  fun invalidOrReversedDatesFailClosed() {
    assertTrue(TicketVisualDateGlyphRecognizer.recognize(render("9908202625082026", false), 96, 7).isEmpty())
    assertTrue(TicketVisualDateGlyphRecognizer.recognize(render("2508202624082026", false), 96, 7).isEmpty())
  }

  @Test
  fun validityEndpointsSplitAcrossTwoRowsRemainAssociated() {
    val pixels = IntArray(96 * 20) { 0xfff8f8f8.toInt() }
    renderInto(pixels, 96, "24082026", 0, brightGlyph = false)
    renderInto(pixels, 96, "25082026", 11, brightGlyph = false)

    val ranges = TicketVisualDateGlyphRecognizer.recognize(pixels, 96, 20)
    assertEquals(1, ranges.size)
  }

  @Test
  fun currentThinSansDateRangeIsRecognizedInBothThemes() {
    val value = "2408202622092026"
    val light = render(value, brightGlyph = false, glyphs = thinTemplates)
    val dark = render(value, brightGlyph = true, glyphs = thinTemplates)

    assertEquals(1, TicketVisualDateGlyphRecognizer.recognize(light, 96, 7).size)
    assertEquals(1, TicketVisualDateGlyphRecognizer.recognize(dark, 96, 7).size)
  }

  @Test
  fun antialiasedGrayThinDateRangeIsRecognizedWithoutGuessing() {
    val pixels = render("2408202622092026", brightGlyph = false, glyphs = thinTemplates)
    pixels.indices.forEach { index ->
      if (pixels[index] == 0xff101010.toInt()) pixels[index] = 0xffa0a0a0.toInt()
    }

    assertEquals(1, TicketVisualDateGlyphRecognizer.recognize(pixels, 96, 7).size)
  }

  @Test
  fun undersampledFivePixelThinDatesFailClosedInsteadOfBeingGuessed() {
    val source = render("2408202622092026", brightGlyph = false, glyphs = thinTemplates)
    val width = 72
    val height = 5
    val reduced = IntArray(width * height) { index ->
      val x = index % width
      val y = index / width
      source[(y * 7 / height) * 96 + x * 96 / width]
    }

    assertTrue(TicketVisualDateGlyphRecognizer.recognize(reduced, width, height).isEmpty())
  }

  @Test
  fun ambiguousCorruptedThinGlyphFailsClosed() {
    val ambiguous = booleanArrayOf(
      false, true, true, true, false,
      false, false, false, false, true,
      false, false, true, true, false,
      false, false, false, false, true,
      false, false, false, false, true,
      false, false, false, false, true,
      false, true, true, true, false
    )
    assertEquals('?', TicketVisualDateGlyphRecognizer.recognizeGlyph(ambiguous, 5, 7))
  }

  @Test
  fun bilinearThinEightMatchesSanitizedCurrentTemplateBeforeTopology() {
    val rows = listOf(
      "...###..",
      ".#######",
      ".##...##",
      ".##...##",
      "..#####.",
      ".##...##",
      "##.....#",
      ".##...##",
      ".#######"
    )
    val glyph = glyph(rows)

    assertEquals('8', TicketVisualDateGlyphRecognizer
      .recognizeGlyphWithoutTopologyForTest(glyph, 8, rows.size))
    assertEquals('8', TicketVisualDateGlyphRecognizer.recognizeGlyph(glyph, 8, rows.size))
  }

  @Test
  fun bilinearThinNineMatchesSanitizedCurrentTemplateBeforeTopology() {
    val phaseVariants = listOf(
      listOf(
        "..###...", ".######.", "##....##", "##....##", "##....##",
        ".#######", "......##", ".....##.", "..####.."
      ),
      listOf(
        "...##...", ".##..##.", "##....#.", "##....##", "##....##",
        ".#######", "......#.", ".....##.", "..####.."
      )
    )

    phaseVariants.forEach { rows ->
      val candidate = glyph(rows)
      assertEquals('9', TicketVisualDateGlyphRecognizer
        .recognizeGlyphWithoutTopologyForTest(candidate, 8, rows.size))
      assertEquals('9', TicketVisualDateGlyphRecognizer.recognizeGlyph(candidate, 8, rows.size))
    }
  }

  @Test
  fun androidBilinearZeroUsesBalancedSidesWithoutWeakeningTemplateMargin() {
    val phaseVariants = listOf(
      listOf(
        "...##..", ".##..##", "##....#", "#.....#", "#.....#",
        "#.....#", "#.....#", "##...##", ".#####."
      ),
      listOf(
        "...##...", ".##..##.", ".#....##", "##....##", "##.....#",
        "##.....#", ".#....##", ".#....##", "..#####."
      )
    )

    phaseVariants.forEach { rows ->
      val candidate = glyph(rows)
      assertEquals('?', TicketVisualDateGlyphRecognizer.recognizeGlyphWithoutTopologyForTest(
        candidate, rows.first().length, rows.size
      ))
      assertEquals('0', TicketVisualDateGlyphRecognizer.recognizeGlyph(
        candidate, rows.first().length, rows.size
      ))
    }
  }

  @Test
  fun zeroTopologyRejectsOpenZeroAndLetterLikeOutlines() {
    val rejects = listOf(
      // Current phase zero with a one-pixel opening through the top cap.
      listOf(
        "...#...", ".##..##", "##....#", "#.....#", "#.....#",
        "#.....#", "#.....#", "##...##", ".#####."
      ),
      // H-like balanced sides: strong top/bottom rows are not a closed counter.
      listOf(
        "##...##", "##...##", "##...##", "#######", "##...##",
        "##...##", "##...##", "##...##", "##...##"
      ),
      // C-like/open-zero outline with no closed right side.
      listOf(
        "..#####", ".##....", "##.....", "##.....", "##.....",
        "##.....", "##.....", ".##....", "..#####"
      )
    )

    rejects.forEachIndexed { index, rows ->
      assertEquals(
        "open zero impostor $index",
        '?',
        TicketVisualDateGlyphRecognizer.recognizeGlyph(
          glyph(rows), rows.first().length, rows.size
        )
      )
    }
  }

  @Test
  fun zeroTopologyRejectsOpenOutlineWithTinyAntialiasPinhole() {
    val rows = listOf(
      "###.###.", ".##..##.", "##....##", "#..#...#", "#.#.#..#",
      "#.#.#..#", "#..#...#", "#......#", ".######."
    )

    assertFalse(TicketVisualDateGlyphRecognizer.looksLikeTopologyZeroForTest(
      glyph(rows), rows.first().length, rows.size
    ))
    assertEquals('?', TicketVisualDateGlyphRecognizer.recognizeGlyph(
      glyph(rows), rows.first().length, rows.size
    ))
  }

  @Test
  fun zeroTopologyDoesNotCaptureTheCurrentSix() {
    val rows = thinTemplates.getValue('6')
    assertEquals('6', TicketVisualDateGlyphRecognizer.recognizeGlyph(
      glyph(rows), rows.first().length, rows.size
    ))
  }

  @Test
  fun topologyDoesNotInventDigitsFromImpostorsOrDamagedCounters() {
    val impostors = listOf(
      // Two-counter letter shape whose best bounded decimal template is not an eight.
      listOf(
        "######..", "##....##", "##....##", "######..", "##....##",
        "##....##", "##....##", "######..", "........"
      ),
      // Symmetric zero: one centred counter and no right-heavy tail.
      listOf(
        "..####..", ".##..##.", "##....##", "##....##", "##....##",
        "##....##", "##....##", ".##..##.", "..####.."
      ),
      // Lower-counter six: the only counter is not in the upper half.
      listOf(
        "..####..", ".##.....", "##......", "######..", "##....##",
        "##....##", "##....##", ".##..##.", "..####.."
      ),
      // Damaged eight with both counters opened by the same edge cut.
      listOf(
        "...###..", ".######.", ".##.....", ".##.....", "..#####.",
        ".##.....", ".##.....", ".######.", "...###.."
      ),
      // Upper-loop shape with a left rather than right lower tail.
      listOf(
        "..####..", ".##..##.", "##....##", "##....##", ".######.",
        ".##.....", ".##.....", ".##.....", "..####.."
      )
    )

    impostors.forEachIndexed { index, rows ->
      val recognized = TicketVisualDateGlyphRecognizer.recognizeGlyph(
        glyph(rows), rows.first().length, rows.size
      )
      assertFalse("impostor $index recognized as $recognized", recognized == '8' || recognized == '9')
    }
  }

  @Test
  fun damagedEightInFullDateRowFailsClosed() {
    val damaged = thinTemplates + ('8' to listOf(
      "###..", "#....", "#....", "###..", "#....", "#....", "###.."
    ))

    assertTrue(TicketVisualDateGlyphRecognizer.recognize(
      render("2408202622092026", brightGlyph = false, glyphs = damaged),
      96,
      7
    ).isEmpty())
  }

  @Test
  fun openZeroInFullDateRowFailsClosed() {
    val closedZero = listOf(
      "...##..", ".##..##", "##....#", "#.....#", "#.....#",
      "#.....#", "#.....#", "##...##", ".#####."
    )
    val openZero = closedZero.toMutableList().also { it[0] = "...#..." }
    val value = "2408202622092026"
    val closed = renderMixedHeightDate(value, closedZero)
    val open = renderMixedHeightDate(value, openZero)

    assertEquals(1, TicketVisualDateGlyphRecognizer.recognize(closed, value.length * 10, 9).size)
    assertTrue(TicketVisualDateGlyphRecognizer.recognize(open, value.length * 10, 9).isEmpty())
  }

  @Test
  fun opaqueAnchorUsesDurablePrivateSaltAndAtomicReplacement() {
    val source = String(Files.readAllBytes(Paths.get(
      "src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketVisualDateGlyphRecognizer.java"
    )))
    assertTrue(source.contains("/data/local/pixel-stack/state/ticket-visual-anchor-salt.bin"))
    assertTrue(source.contains("existing.length == 32"))
    assertTrue(source.contains("MessageDigest.getInstance(\"SHA-256\")"))
    assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"))
    assertTrue(source.contains("PosixFilePermissions.fromString(\"rw-------\")"))
  }

  private fun render(
    value: String,
    brightGlyph: Boolean,
    glyphs: Map<Char, List<String>> = templates
  ): IntArray {
    val background = if (brightGlyph) 0xff101010.toInt() else 0xfff8f8f8.toInt()
    val pixels = IntArray(96 * 7) { background }
    renderInto(pixels, 96, value, 0, brightGlyph, glyphs)
    return pixels
  }

  private fun renderInto(
    pixels: IntArray,
    width: Int,
    value: String,
    top: Int,
    brightGlyph: Boolean,
    glyphs: Map<Char, List<String>> = templates
  ) {
    val foreground = if (brightGlyph) 0xfff8f8f8.toInt() else 0xff101010.toInt()
    value.forEachIndexed { index, character ->
      val rows = glyphs.getValue(character)
      rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, bit ->
          if (bit == '#') pixels[(top + y) * width + index * 6 + x] = foreground
        }
      }
    }
  }

  private fun glyph(rows: List<String>): BooleanArray {
    val width = rows.first().length
    return BooleanArray(width * rows.size).also { result ->
      rows.forEachIndexed { y, row ->
        require(row.length == width)
        row.forEachIndexed { x, value -> result[y * width + x] = value == '#' }
      }
    }
  }

  private fun renderMixedHeightDate(value: String, zeroRows: List<String>): IntArray {
    val width = value.length * 10
    val pixels = IntArray(width * 9) { 0xfff8f8f8.toInt() }
    value.forEachIndexed { index, character ->
      val rows = if (character == '0') zeroRows else thinTemplates.getValue(character)
      val top = if (rows.size == 9) 0 else 1
      rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, bit ->
          if (bit == '#') pixels[(top + y) * width + index * 10 + x] = 0xff101010.toInt()
        }
      }
    }
    return pixels
  }

  private val templates = mapOf(
    '0' to listOf(".###.", "##.##", "##.##", "##.##", "##.##", "##.##", ".###."),
    '1' to listOf("..##.", ".###.", "..##.", "..##.", "..##.", "..##.", ".####"),
    '2' to listOf(".###.", "##.##", "...##", "..##.", ".##..", "##...", "#####"),
    '3' to listOf("####.", "...##", "...##", ".###.", "...##", "...##", "####."),
    '4' to listOf("...##", "..###", ".#.##", "##.##", "#####", "...##", "...##"),
    '5' to listOf("#####", "##...", "##...", "####.", "...##", "...##", "####."),
    '6' to listOf(".###.", "##...", "##...", "####.", "##.##", "##.##", ".###."),
    '7' to listOf("#####", "...##", "..##.", "..##.", ".##..", ".##..", ".##.."),
    '8' to listOf(".###.", "##.##", "##.##", ".###.", "##.##", "##.##", ".###."),
    '9' to listOf(".###.", "##.##", "##.##", ".####", "...##", "...##", ".###.")
  )

  private val thinTemplates = mapOf(
    '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
    '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
    '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
    '5' to listOf("#####", "#....", "#....", "####.", "....#", "....#", "####."),
    '6' to listOf(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
    '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
    '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
    '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###.")
  )
}
