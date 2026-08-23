import java.io.Serializable;

class Input {
    public static void main(String[] args) {
        Object obj = "hello";
        Serializable s = (Serializable & Comparable<?>) obj;
    }
}
