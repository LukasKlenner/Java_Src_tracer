class Input {
    private static String getMessage() {
        return "error";
    }

    public static void main(String[] args) {
        try {
            throw new RuntimeException(getMessage());
        } catch (RuntimeException e) {
            int x = 1;
        }
    }
}
