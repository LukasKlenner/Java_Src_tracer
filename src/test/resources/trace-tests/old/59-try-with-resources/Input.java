class MyResource implements AutoCloseable {
    public void close() {
        // cleanup
    }

    int getValue() {
        return 42;
    }
}

class Input {
    public static void main(String[] args) {
        try (MyResource r = new MyResource()) {
            int x = r.getValue();
        }
    }
}
