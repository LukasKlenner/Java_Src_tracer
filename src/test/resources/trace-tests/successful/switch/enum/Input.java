class Input {

    enum E {
        A, B
    }

    void m() {

    }

    public static void main(String[] args) {
        Input obj = new Input();
        E a = E.A;

        switch (a) {
            case E.A: {
                obj.m();
                break;
            }
            case E.B: {
                int y = 2;
                break;
            }
            default: {
                int z = 3;
                break;
            }
        }
    }
}
