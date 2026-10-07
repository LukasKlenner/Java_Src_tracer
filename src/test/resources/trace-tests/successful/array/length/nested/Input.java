class Input {

    public int[] getArray() {
        return new int[3];
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int len = obj.getArray().length;
    }
}
