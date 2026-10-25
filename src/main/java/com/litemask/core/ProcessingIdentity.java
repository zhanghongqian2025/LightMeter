package com.litemask.core;

/** 扫描实际使用的工具与模型身份；记录值不可变，可安全绑定到旧任务。 */
public record ProcessingIdentity(
        String toolVersion,
        String toolArtifactSha256,
        boolean usedModel,
        String modelId,
        String modelVersionKey,
        String modelPackageSha256,
        String modelSigningKeyId,
        boolean signatureVerified) {

    /** 在扫描持有模型读锁时提供与实际推理来源一致的身份快照。 */
    @FunctionalInterface
    public interface Provider {
        ProcessingIdentity snapshot();
    }

    public ProcessingIdentity {
        toolVersion = toolVersion == null || toolVersion.isBlank() ? "未知" : toolVersion;
        requireDigestShape("toolArtifactSha256", toolArtifactSha256);
        requireDigestShape("modelPackageSha256", modelPackageSha256);
        if (!usedModel && (modelId != null || modelVersionKey != null || modelPackageSha256 != null
                || modelSigningKeyId != null || signatureVerified)) {
            throw new IllegalArgumentException("未使用模型时不能声明模型身份或验签状态");
        }
        if (signatureVerified && (modelVersionKey == null || modelPackageSha256 == null
                || modelSigningKeyId == null)) {
            throw new IllegalArgumentException("验签模型必须带版本键、发布包摘要和签名密钥标识");
        }
    }

    public static ProcessingIdentity withoutModel() {
        return new ProcessingIdentity(ToolIdentity.version(), ToolIdentity.artifactSha256(),
                false, null, null, null, null, false);
    }

    /** 严格法律生产审批要求实际 jar 可绑定、实际使用模型、且模型发布签名可复验。 */
    public boolean strictProductionEligible() {
        return !"未知".equals(toolVersion) && toolArtifactSha256 != null
                && usedModel && signatureVerified;
    }

    private static void requireDigestShape(String name, String value) {
        if (value != null && !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " 必须是 64 位小写 SHA-256");
        }
    }
}
