package com.arthur.labops.payment.channel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * T+1 settlement file, CSV. Deliberately a flat file rather than a shared table:
 * reconciliation must read the channel's version of the truth through the same
 * narrow interface a real integration would, not by peeking at local state.
 */
public final class ChannelBillFile {

    static final String HEADER = "channel_txn_id,order_no,type,amount_cents,status,occurred_at";

    private ChannelBillFile() {
    }

    public static void write(Path target, List<ChannelEntry> entries) {
        StringBuilder builder = new StringBuilder(HEADER).append('\n');
        for (ChannelEntry entry : entries) {
            builder.append(entry.channelTxnId()).append(',')
                    .append(entry.orderNo()).append(',')
                    .append(entry.type().name()).append(',')
                    .append(entry.amountCents()).append(',')
                    .append(entry.status()).append(',')
                    .append(entry.occurredAt().toString()).append('\n');
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法写入渠道账单文件 " + target, exception);
        }
    }

    public static List<ChannelEntry> read(Path source) {
        List<String> lines;
        try {
            lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取渠道账单文件 " + source, exception);
        }
        List<ChannelEntry> entries = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("channel_txn_id")) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length != 6) {
                throw new IllegalStateException("渠道账单行格式非法: " + line);
            }
            entries.add(new ChannelEntry(
                    parts[0],
                    parts[1],
                    ChannelEntryType.valueOf(parts[2]),
                    Long.parseLong(parts[3]),
                    parts[4],
                    Instant.parse(parts[5])));
        }
        return entries;
    }
}
