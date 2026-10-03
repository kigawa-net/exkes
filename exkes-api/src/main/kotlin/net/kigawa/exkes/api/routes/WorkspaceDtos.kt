package net.kigawa.exkes.api.routes

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.kigawa.exkes.common.domain.Workspace

/** Serializes java.time.Instant as an ISO-8601 UTC string (e.g. 2026-10-01T00:00:00Z). */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
data class CreateWorkspaceRequest(
    val name: String,
    val storageSizeBytes: Long? = null,
    val labels: Map<String, String>? = null,
)

@Serializable
data class WorkspaceResponse(
    val id: String,
    val name: String,
    val status: String,
    val storageClass: String,
    val storageSizeBytes: Long,
    val pvcName: String?,
    val errorMessage: String?,
    val labels: Map<String, String>,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant,
)

@Serializable
data class WorkspaceListResponse(
    val items: List<WorkspaceResponse>,
    val total: Long,
    val limit: Int,
    val offset: Int,
)

/** Common error format (plan chapter 6.2). */
@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)

fun Workspace.toResponse(): WorkspaceResponse = WorkspaceResponse(
    id = id,
    name = name,
    status = status.name,
    storageClass = storageClass,
    storageSizeBytes = storageSizeBytes,
    pvcName = pvcName,
    errorMessage = errorMessage,
    labels = labels,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
