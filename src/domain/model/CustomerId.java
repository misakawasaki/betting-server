package domain.model;

import java.util.Objects;

public final class CustomerId {

    private final int value;

    private CustomerId(int value) {
        this.value = value;
    }

    public static CustomerId of(int value) {
        return new CustomerId(value);
    }

    public int asInt() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CustomerId that = (CustomerId) o;
        return value == that.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}

