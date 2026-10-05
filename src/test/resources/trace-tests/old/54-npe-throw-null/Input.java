class Input {
    public static void main(String[] args) {
        RuntimeException ex = new RuntimeException("test");
        try {
            throw ex;
        } catch (RuntimeException e) {
            int x = 1;
        }
    }
}
