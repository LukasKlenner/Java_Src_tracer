class Input {

    public int getSize() {
        return 5;
    }

    public Integer[] getNumbers() {
        Input obj = new Input();
        return new Integer[obj.getSize()];
    }

    public static void main(String[] args) {
        Input obj = new Input();
        Object[] arr = new Object[]{
            new String[]{"hello", "world"},
            obj.getNumbers(),
        };
    }
}
