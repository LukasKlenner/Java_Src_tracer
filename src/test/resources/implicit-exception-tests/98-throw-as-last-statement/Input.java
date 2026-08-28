class Input {

    public static void main(String[] args) {
        try {
            throwSomething();
        } catch (Exception e) {
            int x = 1;
        }
    }

    private static void throwSomething() throws Exception {
        throw new Exception("This is an exception");
    }
}
