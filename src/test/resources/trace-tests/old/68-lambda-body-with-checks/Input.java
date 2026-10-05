import java.util.function.IntSupplier;

class Input {
    public static void main(String[] args) {
        int[] arr = new int[]{10, 20, 30};
        IntSupplier supplier = () -> {
            return arr[0];
        };
        int x = supplier.getAsInt();
    }
}
