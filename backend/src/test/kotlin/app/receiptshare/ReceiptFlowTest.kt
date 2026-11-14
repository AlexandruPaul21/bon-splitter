package app.receiptshare

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files

@SpringBootTest
@AutoConfigureMockMvc
class ReceiptFlowTest @Autowired constructor(private val mvc: MockMvc, private val json: ObjectMapper) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dataDir(registry: DynamicPropertyRegistry) {
            registry.add("receipt-share.data-dir") { Files.createTempDirectory("receipt-share-test-").toString() }
        }
    }

    @Test
    fun `private drafts require auth and only confirmed receipts change balances`() {
        mvc.perform(get("/api/groups")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
            .content("""{"password":"long organizer password"}""")).andExpect(status().isForbidden)

        val setup = mvc.perform(post("/api/auth/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"password":"long organizer password"}"""))
            .andExpect(status().isOk).andReturn()
        val session = setup.request.session as MockHttpSession
        val group = mvc.perform(post("/api/groups").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Home","currency":"EUR"}"""))
            .andExpect(status().isOk).andReturn()
        val groupId = json.readTree(group.response.contentAsString)["id"].asText()
        val first = mvc.perform(post("/api/groups/$groupId/members").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Alice"}"""))
            .andExpect(status().isOk).andReturn()
        val aliceId = json.readTree(first.response.contentAsString)["members"][0]["id"].asText()
        val second = mvc.perform(post("/api/groups/$groupId/members").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Bob"}"""))
            .andExpect(status().isOk).andReturn()
        val bobId = json.readTree(second.response.contentAsString)["members"][1]["id"].asText()
        mvc.perform(post("/api/groups/$groupId/members").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Alice"}"""))
            .andExpect(status().isConflict)
        val draft = mvc.perform(post("/api/groups/$groupId/receipts").session(session).with(csrf()))
            .andExpect(status().isOk).andReturn()
        val receiptId = json.readTree(draft.response.contentAsString)["id"].asText()
        val before = mvc.perform(get("/api/groups/$groupId/balances").session(session)).andExpect(status().isOk).andReturn()
        assertEquals(0, json.readTree(before.response.contentAsString)["members"].sumOf { it["balance"].asInt() })
        mvc.perform(post("/api/receipts/$receiptId/confirm").session(session).with(csrf())).andExpect(status().isBadRequest)
        val edit = """{"store":"Market","date":"2026-11-04","subtotal":1000,"discount":0,"tax":0,"tip":0,"total":1000,"payerId":"$aliceId",
            "items":[{"description":"Coffee","amount":1000,"mode":"PERCENT","shares":[{"memberId":"$aliceId","value":5000},{"memberId":"$bobId","value":5000}]}]}"""
        mvc.perform(put("/api/receipts/$receiptId").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isOk)
        mvc.perform(post("/api/receipts/$receiptId/confirm").session(session).with(csrf())).andExpect(status().isOk)
        mvc.perform(put("/api/receipts/$receiptId").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isConflict)
        val balances = mvc.perform(get("/api/groups/$groupId/balances").session(session)).andExpect(status().isOk).andReturn()
        val tree = json.readTree(balances.response.contentAsString)
        assertEquals(500, tree["members"][0]["balance"].asInt())
        assertEquals(-500, tree["members"][1]["balance"].asInt())
        assertEquals(500, tree["settlements"][0]["amount"].asInt())
        mvc.perform(get("/api/receipts/$receiptId/file").session(session)).andExpect(status().isNotFound)
    }
}
