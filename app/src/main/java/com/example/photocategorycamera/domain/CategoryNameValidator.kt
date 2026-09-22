package com.example.photocategorycamera.domain

data class ValidationResult(
    val normalizedName: String,
    val error: String? = null,
) {
    val isValid: Boolean get() = error == null
}

object CategoryNameValidator {
    private const val MAX_LENGTH = 40

    fun validate(input: String): ValidationResult {
        val normalized = input.trim()
        return when {
            normalized.isEmpty() -> ValidationResult(normalized, "分类名称不能为空")
            normalized.length > MAX_LENGTH -> ValidationResult(normalized, "分类名称不能超过 40 个字符")
            normalized.any { it == '/' || it == '\\' || it.isISOControl() } ->
                ValidationResult(normalized, "分类名称不能包含 /、\\ 或控制字符")
            else -> ValidationResult(normalized)
        }
    }
}
