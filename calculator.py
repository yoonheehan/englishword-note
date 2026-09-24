def calculate(number1, operator, number2):
    if operator == "+":
        return number1 + number2
    if operator == "-":
        return number1 - number2
    if operator == "*":
        return number1 * number2
    if operator == "/":
        if number2 == 0:
            raise ZeroDivisionError("0으로 나눌 수 없습니다.")
        return number1 / number2
    raise ValueError("지원하지 않는 연산자입니다.")


def main():
    print("간단한 계산기 (+, -, *, /)")
    print("종료하려면 첫 번째 숫자 대신 q를 입력하세요.")

    while True:
        first_input = input("\n첫 번째 숫자: ").strip()
        if first_input.lower() == "q":
            print("계산기를 종료합니다.")
            break

        try:
            number1 = float(first_input)
            operator = input("연산자: ").strip()
            number2 = float(input("두 번째 숫자: ").strip())
            result = calculate(number1, operator, number2)

            if result.is_integer():
                result = int(result)
            print(f"결과: {result}")
        except ValueError as error:
            print(f"입력 오류: {error}")
        except ZeroDivisionError as error:
            print(f"계산 오류: {error}")


if __name__ == "__main__":
    main()
