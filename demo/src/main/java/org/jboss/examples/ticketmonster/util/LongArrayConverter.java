package org.jboss.examples.ticketmonster.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.*;

@Converter
public class LongArrayConverter implements AttributeConverter<long[][], byte[]> {

    @Override
    public byte[] convertToDatabaseColumn(long[][] attribute) {
        if (attribute == null) return null;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(attribute);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize long[][]", e);
        }
    }

    @Override
    public long[][] convertToEntityAttribute(byte[] dbData) {
        if (dbData == null) return null;
        try (ByteArrayInputStream bis = new ByteArrayInputStream(dbData);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (long[][]) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize long[][]", e);
        }
    }
}
