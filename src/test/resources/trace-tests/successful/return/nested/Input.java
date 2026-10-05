class Input {

    int methodWithReturn() {
        int[] arr = new int[]{1, 2, 3};
        return arr[0];
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.methodWithReturn();
    }
}
