package app.receiptshare

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.stereotype.Service
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Configuration
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.authorizeHttpRequests {
            it.requestMatchers("/api/auth/me", "/api/auth/csrf", "/api/auth/login", "/api/auth/setup").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
        }
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
        http.csrf {
            it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
        }
        http.securityContext { it.securityContextRepository(HttpSessionSecurityContextRepository()) }
        http.exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        http.formLogin { it.disable() }
        http.httpBasic { it.disable() }
        http.logout { it.disable() }
        return http.build()
    }
}

@Service
class OrganizerAuth(private val jdbc: JdbcTemplate) {
    private val encoder = BCryptPasswordEncoder()
    private val attempts = ConcurrentHashMap<String, MutableList<Instant>>()
    private val contexts = HttpSessionSecurityContextRepository()

    fun exists(): Boolean = jdbc.queryForObject("SELECT COUNT(*) FROM organizer", Int::class.java)!! > 0

    fun setup(password: String, request: HttpServletRequest, response: HttpServletResponse) {
        if (password.length < 4 || password.length > 256) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be 4–256 characters")
        try {
            jdbc.update("INSERT INTO organizer(id, password_hash) VALUES (1, ?)", encoder.encode(password))
        } catch (_: org.springframework.dao.DataAccessException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Organizer already configured")
        }
        authenticate(request, response)
    }

    fun login(password: String, request: HttpServletRequest, response: HttpServletResponse) {
        val ip = request.remoteAddr
        val now = Instant.now()
        val record = attempts.computeIfAbsent(ip) { mutableListOf() }
        synchronized(record) {
            record.removeIf { it.isBefore(now.minusSeconds(15 * 60)) }
            if (record.size >= 5) throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please try again in 15 minutes")
            val hash = jdbc.query("SELECT password_hash FROM organizer WHERE id = 1", { rs, _ -> rs.getString(1) }).firstOrNull()
            if (hash == null || !encoder.matches(password, hash)) {
                record.add(now)
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect password")
            }
            record.clear()
        }
        request.getSession(false)?.invalidate()
        authenticate(request, response)
    }

    private fun authenticate(request: HttpServletRequest, response: HttpServletResponse) {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken("organizer", null, listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ORGANIZER")))
        SecurityContextHolder.setContext(context)
        contexts.saveContext(context, request, response)
    }
}
