package org.ferabrasil.quests.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.ferabrasil.quests.protocol.model.ErrorCode
import org.ferabrasil.quests.protocol.model.ErrorDto
import org.slf4j.LoggerFactory

/** Thrown by services/routes; rendered as `{"error","message"}` (backend.md §5.9). */
class ApiException(val code: ErrorCode, override val message: String? = null) : RuntimeException(message)

private val log = LoggerFactory.getLogger("org.ferabrasil.quests.server.Errors")

fun Application.configureErrors() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(HttpStatusCode.fromValue(e.code.httpStatus), ErrorDto(e.code.wire, e.message))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorDto(ErrorCode.TOO_LARGE.wire))
        }
        // Message deliberately not echoed: it may contain fragments of the request body.
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(ErrorCode.BAD_REQUEST.wire, "malformed body"))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(ErrorCode.BAD_REQUEST.wire))
        }
        exception<Throwable> { call, e ->
            // Only the exception type: messages can carry user data (rule §1.2).
            log.error("unhandled {}", e::class.qualifiedName)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto(ErrorCode.INTERNAL.wire))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorDto(ErrorCode.NOT_FOUND.wire))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ErrorDto(ErrorCode.NOT_FOUND.wire))
        }
    }
}
