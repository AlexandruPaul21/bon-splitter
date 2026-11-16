package app.receiptshare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReceiptParserTest {
    @Test
    fun `kaufland receipt with promos deposits and multi line items`() {
        val edit = ReceiptParser.parse(KAUFLAND_ALCOOL, "note").edit
        assertEquals("Kaufland", edit.store)
        assertEquals(LocalDate.of(2026, 8, 13), edit.date)
        assertEquals(24_352, edit.subtotal, "Effective prices add up to the printed total")
        assertEquals(3_082, edit.discount)
        assertEquals(24_352, edit.total)
        assertEquals(30, edit.items.size)
        assertTrue(edit.items.any { it.description == "NUTELLA.200G" && it.amount == 1_399L })
        assertTrue(edit.items.any { it.description == "CIUCAS BERE 0,5L (7 x 2,69)" && it.amount == 1_883L })
        assertTrue(edit.items.any { it.description == "GARANTIE AMBALAJ * (7 x 0,50)" && it.amount == 350L })
        assertTrue(edit.items.any { it.description == "URSUS COOLER 4x0,5L" && it.amount == 1_337L })
        assertTrue(edit.items.all { it.amount > 0 })
    }

    @Test
    fun `kaufland receipt with subtotal and card promo section`() {
        val edit = ReceiptParser.parse(KAUFLAND_GROCERIES, "note").edit
        assertEquals("Kaufland", edit.store)
        assertEquals(LocalDate.of(2026, 5, 8), edit.date)
        assertEquals(27_412, edit.subtotal, "Effective prices add up to the printed total")
        assertEquals(4_450, edit.discount)
        assertEquals(27_412, edit.total)
        assertTrue(edit.items.any { it.description == "NEW YORK MANZAT (0,320 KG)" && it.amount == 5_306L })
        assertTrue(edit.items.any { it.description == "FP AFINE 125 GR (3 x 12,99)" && it.amount == 1_792L })
        assertTrue(edit.items.any { it.description == "PORTOCALE PL. 1 KG" && it.amount == 430L })
        assertTrue(edit.items.none { it.description.contains("XTRA", ignoreCase = true) })
        assertTrue(edit.items.none { it.description.contains("Subtotal", ignoreCase = true) })
    }

    @Test
    fun `lidl photo receipt parses unit price lines and redemeption discounts`() {
        val edit = ReceiptParser.parse(LIDL_PHOTO, "note").edit
        assertEquals("Lidl", edit.store)
        assertEquals(LocalDate.of(2026, 7, 26), edit.date)
        assertEquals(6_728, edit.subtotal, "Effective prices add up to the printed total")
        assertEquals(2_500, edit.discount)
        assertEquals(6_728, edit.total)
        assertEquals(11, edit.items.size)
        assertTrue(edit.items.any { it.description == "Pepene verde" && it.amount == 650L })
        assertTrue(edit.items.any { it.description == "Gogoasa glazura cioco." && it.amount == 131L })
        assertTrue(edit.items.any { it.description == "Berliner cu umplutura" && it.amount == 203L })
        assertTrue(edit.items.any { it.description == "Snacks cartofi sare" && it.amount == 451L })
        assertTrue(edit.items.none { it.description.contains("BUC", ignoreCase = true) || it.description.contains("KG x", ignoreCase = true) })
    }

    @Test
    fun `receipt without total line falls back to the effective subtotal`() {
        val edit = ReceiptParser.parse(LIDL_NO_TOTAL, "note").edit
        assertEquals("Lidl", edit.store)
        assertEquals(14_742, edit.subtotal)
        assertEquals(2_739, edit.discount)
        assertEquals(14_742, edit.total)
        assertEquals(8, edit.items.size)
        assertTrue(edit.items.none { it.description.contains("BUC", ignoreCase = true) })
        assertTrue(edit.items.all { it.amount > 0 })
    }

    @Test
    fun `kaufland economisiti already nets the saving into the printed price`() {
        val edit = ReceiptParser.parse(KAUFLAND_ECONOMISITI, "note").edit
        assertEquals("Kaufland", edit.store)
        assertEquals(LocalDate.of(2026, 10, 26), edit.date)
        assertEquals(12_906, edit.subtotal, "Items keep their printed (net) prices")
        assertEquals(630, edit.discount)
        assertEquals(12_906, edit.total, "The printed total is used as-is")
        assertEquals(12, edit.items.size)
        val aldis = edit.items.single { it.description == "ALDIS MICI VITA PORC" }
        assertEquals(869L, aldis.amount, "The printed price is the effective price")
        assertEquals(630L, aldis.discount)
        assertTrue(edit.items.all { it.amount > 0 })
    }

    @Test
    fun `empty text yields empty draft`() {
        val edit = ReceiptParser.parse("", "note").edit
        assertEquals("", edit.store)
        assertEquals(0, edit.subtotal)
        assertEquals(0, edit.discount)
        assertEquals(0, edit.total)
        assertEquals(emptyList<ReceiptItem>(), edit.items)
    }

    private val KAUFLAND_ALCOOL = """
        You earned 243 points for this purchase.
        Receipt Copy
                                          Preț RON
          Alimente
          NUTELLA.200G                     13,99 B
          HREAN DE MASA190G                 8,49 B
          TAB.SRIRACHA.256ML               32,99 B
          Băuturi alcoolice
          CIUCAS BERE 0,5L
           7 * 2,69                        18,83 B
          GARANTIE AMBALAJ *
           7 * 0,50                         3,50 D
          TIMISOREANA 0,5L                  4,49 B
          Promoție                         -1,49
          GARANTIE AMBALAJ *                0,50 D
          NEUMARKT 5+1                     20,95 B
          Promoție                         -5,87
          GARANTIE AMBALAJ *
           6 * 0,50                         3,00 D
          URSUS CIRESE 4x0,5L              14,97 B
          Promoție                         -4,94
          GARANTIE AMBALAJ *
           4 * 0,50                         2,00 D
          URSUS COOLER 4x0,5L              19,96 B
          Promoție                         -6,59
          GARANTIE AMBALAJ *
           4 * 0,50                         2,00 D
          URSUS COOLER 4X0,5L              14,97 B
          Promoție                         -4,94
          GARANTIE AMBALAJ *
           4 * 0,50                         2,00 D
          Băuturi nonalcoolice
          PEPSI ZERO TWIST 2L              10,99 B
          Promoție                         -3,30
          GARANTIE AMBALAJ *                0,50 D
          PEPSI SUC 2L                     12,29 B
          Promoție                         -3,69
          GARANTIE AMBALAJ *                0,50 D
          Dulciuri
          VEL PITAR BERE 230G               4,79 C
          ROSHEN CACAO 216 G                7,89 B
          FELEACUL HALVA 200G               8,12 B
          HEIDI BOUQUETTE120G              17,79 B
          POPCORN 80G                       4,59 C
          K. ARAHIDE 200G                   6,09 C
          CHIO POPCORN 80G                  3,99 C
          K.BISCUITI150G                    7,99 B
          ELMAS SEMINTE 200G                5,79 C
          KLC. BISCUITI 250G                7,99 B
          Fashion, Accesorii
          LUMANARI TORT                    12,39 B
          TOTAL                           243,52
          Plată card                        1,20
          Plată card                      242,32
          Rest                              0,00
          TVA %         Brut       Net       Tva
          B=21,00%    204,27    168,82     35,45
          C=11,00%     25,25     22,75      2,50
          D=0,00%      14,00     14,00      0,00
          Kaufland Card XTRA:          xxxxx0906
          Kaufland RO 4700
          Date: 13.08.26 Timp:  18:44:05 Bon: 1943
    """.trimIndent()

    private val KAUFLAND_GROCERIES = """
        You saved 44,50 RON with Kaufland Card
        You earned 274 points for this purchase.
        Receipt Copy
                                          Preț RON
          Alimente
          FAINA ALBA 1KG                    4,89 C
          Carne/ Autoservire carne
          NEW YORK MANZAT
           0,320 KG                        57,57 C
          Congelate
          EDENIA FILE BIBAN                47,89 C
          Delicatese/Convenience/Marinade
          KLC.GUAC.PICANT.150G              8,49 C
          Drogerie/Gospodărie/Hrană animale/Tabac
          DUCK GEL WC 750 ML
           2 * 11,07                       22,14 B
          DUCK ODORIZ.WC2X36G
           2 * 7,99                        15,98 B
          Fructe / Legume / Plante
          BUCHET LALELE                    34,99 B
          PORTOCALE PL. 1 KG                6,29 C
          Kaufland Card XTRA               -1,50
          FP AFINE 125 GR
           3 * 12,99                       38,97 C
          Kaufland Card XTRA              -18,00
          Frumuseţe, Sănătate, Copii
          WIL.AP.RAS12BUC                  41,99 B
          Mopro/Brânză/Ouă
          NAPOLACT CHEF.3X330G             14,25 C
          HOCHLAND MAR.BR2X180             13,39 C
          OLYMPUS COTTAGE 180G              6,79 C
          COV.BR.FAGARAS USOR               4,99 C
          Subtotal              299,12
          ----------------Promoție---------------
          Kaufland Card XTRA              -25,00
          TOTAL                           274,12
          Plată card                        1,20
          Kaufland Card XTRA:          xxxxx0906
          Kaufland RO 1400
          Date: 08.05.26 Timp:  19:24:55 Bon: 57039
    """.trimIndent()

    private val LIDL_PHOTO = """
        S.C. LIDL DISCOUNT S.R.L.
        Strada Eroilor 1-7
        497280 Cluj-Napoca
        Cod Fiscal C.I.F.: 22891860
        Let
        5,274 KG x 1,69
        Pepene verde 8,91 8
        Reducere Lidl Plus
        Economisire in urma reducerilor 2,41-B
        1,008 BUC x 6,19
        Snacks cartofi sare 6.19 B
        Reducere Lidl Plus
        Economisire in urma reducerilor 1, 68-B
        2,000 BUC x 5,99
        Servetele cosmetice 11,98 A
        Reducere Lidl Plus
        Economisire in urma reducerilor 3,24-A
        1,008 BUC x 8,64
        Prosciutto Cotto 8,64 8
        Reducere Lidl Plus
        Economisire in urma reducerilor 2,34-8
        1,000 BUC x 8,45
        Seminte de dovleac natur 8,45 8
        Reducere Lidl Plus
        Economisire in urma reducerilor 2,29-B
        1,008 BUC x 13,99
        PM Mix Nuts Royal 13,99 8
        Reducere Lidl Plus
        Economisire in urma reducerilor 3,79-8
        1,08 BUC x 14,29
        Mozzarella rasa 14,29 8
        Reducere Lidl Plus
        Economisire in urma reducerilor 3,87-8
        1,00 BUC x 8,99
        Aluat de pizza XXL 8,99 B
        Reducere Lidl Plus
        Economisire in urma reducerilor 2,44-8
        0,570 KG x 10,99
        Rosii taranesti 6,26 B
        Reducere Lidl Plus
        Economisire in urma reducerilor 1,70-8
        1,008 BUC x 1,79
        Gogoasa glazura cioco. 1,798
        Reducere Lidl Plus
        Economisire in urma reducerilor 0,48-A
        1,008 BUC x 2,79
        Berliner cu umplutura 2,79 &
        Reducere Lidl Plus
        Economisire in urma reducerilor 0,76-8
        Subtotal
        TOTAL 67,28
        TOTAL TVA 7,87
        TVA A 21, 00% 2,10
        TVA B 11, 00% 5,47
        CARD 67,28
        Casa: 85
        DATA:26/07/2026 RA: 14-32-55
    """.trimIndent()

    private val LIDL_NO_TOTAL = """
        S.C. LIDL DISCOUNT S.R.L.
        Strada Eroilor 1-7
        407288 Cluj-Napoca
        Cod Fiscal C.I.F.: 22891860
        Lei
        1,088 BUC x 39,99
        Multisenzor 360496220 39,99 A
        1,088 BUC x 5,59
        Chipsuri din cartofi 5,59 B
        Reducere Lidl Plus
        Economisire in urma reducerilor 1,12-B
        2,088 BUC x 8,99
        Lay's Chipsuri cu sare 17,98 B
        1,088 BUC x 2,79
        Hrana umeda pentru caini 2,79 B
        Reducere Lidl Plus
        Economisire in urma reducerilor 2,79-B
        1,088 BUC x 3,49
        Hrana umeda pentru caini 3,49 B
        Reducere Lidl Plus
        Economisire in urma reducerilor 3,49-B
        1,088 BUC x 49,99
        Surubelnita 360523166 49,99 A
        1,088 BUC x 34,99
        Trusa unelte@544923 34,99 A
        1,008 BUC x 19,99
        Jucarie plus 19,99 A
        Reducere Lidl Plus
        Economisire in urma reducerilor 19,99-A
        Subtotal 147,42
        TOTAL TVA 23,97
        TVA A 21, 08% 21,69
        TVA B 11, 06% 2,22
        CARD 1,18
        CARD 146,32
        Casa: 85
        DATA:14/10/2026 ORA:14-52-23
    """.trimIndent()

    private val KAUFLAND_ECONOMISITI = """
        You earned 129 points for this purchase.
        Receipt Copy
                                          Preț RON
          Alimente
          HELLMANN S BBQ 250ML             15,39 B
          Autoservire carnati(refr./neref.)
          CARNATI CABANOS                  10,99 C
          Carne/ Autoservire carne
          ALDIS MICI VITA PORC              8,69 C
          Economisiti 42% sau 6,30 RON!
          Fructe / Legume / Plante
          FP AFINE 125 GR                   9,99 C
          BANANE           0,682 KG         4,77 C
          ARDEI KAPIA ROSU
           0,192 KG                         2,11 C
          ARDEI KAPIA ROSU
           0,296 KG                         3,25 C
          Mopro/Brânză/Ouă
          NAPOLACT CHEF.3X330G
           2 * 14,82                       29,64 C
          OLY IAU GREC2%4X150G
           2 * 12,88                       25,76 C
          K.LAP.1,5%1L                      4,39 C
          OLYMPUS COTTAGE 180G
           2 * 6,89                        13,78 C
          Nonfood - Altele
          PUNGA BIO        3 * 0,10         0,30 B
          TOTAL                           129,06
          Plată card                      129,06
          Rest                              0,00
          TVA %         Brut       Net       Tva
          B=21,00%     15,69     12,97      2,72
          C=11,00%    113,37    102,14     11,23
          Kaufland Card XTRA:          xxxxx0906
          Kaufland RO 1400
          Date: 26.10.26 Timp: 16:00:20 Bon: 39131
    """.trimIndent()
}
