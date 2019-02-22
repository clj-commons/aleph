package aleph.utils;

/**
 * NOTE: For internal usage only.
 *
 * Defined to be used as a value wrapper when working with
 * io.netty.util.DomainNameMapping to beat its requirement
 * to have a default value in place.
 *
 * It's usually way better to represent Some() and NONE as
 * 2 different subclasses. But it doesn't really matter for
 * the limited number of use cases.
 */
public class Option<T> {
    protected final T value;
    protected final boolean isSome;

    public static final Option NONE = new Option();

    private Option() {
        value = null;
        isSome = false;
    }

    private Option(T someValue) {
        value = someValue;
        isSome = true;
    }

    public boolean isSome() {
        return isSome;
    }

    public static <T> Option<T> some(final T value) {
        return new Option<T>(value);
    }

    public T get() {
        return this.value;
    }

    @Override
    public String toString() {
        if (!isSome) {
            return "#None";
        }
        return "#Some<" + value.toString() + ">";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Option<?> that = (Option<?>) o;
        if (isSome != that.isSome()) return false;

        return value.equals(that.get());

    }

    @Override
    public int hashCode() {
        if (isSome) {
            return value.hashCode();
        }

        return 0;
    }
}
