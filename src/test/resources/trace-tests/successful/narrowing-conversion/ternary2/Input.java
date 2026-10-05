class Input {
    public static void main(String[] args) {
        boolean condition = true;
        Object result = condition ? 1 : 'a';
        if (result instanceof Character) {
            Character c = (Character) result;
        }
    }
}
