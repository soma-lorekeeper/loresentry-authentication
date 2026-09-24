package com.loresentry.authentication.adapter.out.id;

import com.fasterxml.uuid.Generators;
import com.loresentry.authentication.application.port.out.UserIdGenerator;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.function.Supplier;

public final class UuidUserIdGenerator implements UserIdGenerator {
    private final Supplier<UUID> generator =
            Generators.timeBasedEpochRandomGenerator(new SecureRandom())::generate;

    @Override
    public UUID generate() {
        return generator.get();
    }
}
