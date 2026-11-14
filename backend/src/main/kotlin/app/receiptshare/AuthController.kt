package app.receiptshare

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val auth: OrganizerAuth) {
    data class Credentials(val password: String)
    data class SessionStatus(val authenticated: Boolean, val setupRequired: Boolean)
    data class Token(val token: String, val headerName: String)

    @GetMapping("/me")
    fun me(authentication: Authentication?): SessionStatus =
        SessionStatus(authentication?.principal == "organizer" && authentication.isAuthenticated, !auth.exists())

    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): Token = Token(token.token, token.headerName)

    @PostMapping("/setup")
    fun setup(@RequestBody credentials: Credentials, request: HttpServletRequest, response: HttpServletResponse): SessionStatus {
        auth.setup(credentials.password, request, response)
        return SessionStatus(true, false)
    }

    @PostMapping("/login")
    fun login(@RequestBody credentials: Credentials, request: HttpServletRequest, response: HttpServletResponse): SessionStatus {
        auth.login(credentials.password, request, response)
        return SessionStatus(true, false)
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): SessionStatus {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        return SessionStatus(false, false)
    }
}
