class Input {

    void m() {

    }

    public static void main(String[] args) {
        Input obj = new Input();
        int a = 2;

        switch (a) {
            case 1:
                break;
            case 2: {
                obj.m();
                break;
            }
            default:
                int z = 3;
                break;
        }
    }
}
