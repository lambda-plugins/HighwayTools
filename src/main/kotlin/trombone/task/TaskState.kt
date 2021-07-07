package trombone.task

import com.lambda.client.util.color.ColorHolder

enum class TaskState(val stuckThreshold: Int, val stuckTimeout: Int, val color: ColorHolder) {
    BROKEN(1000, 1000, ColorHolder(111, 0, 0)),
    PLACED(1000, 1000, ColorHolder(53, 222, 66)),
    LIQUID(100, 100, ColorHolder(114, 27, 255)),
    PICKUP(500, 500, ColorHolder(252, 3, 207)),
    RESTOCK(500, 500, ColorHolder(252, 3, 207)),
    OPEN_CONTAINER(500, 500, ColorHolder(252, 3, 207)),
    BREAKING(100, 100, ColorHolder(240, 222, 60)),
    BREAK(20, 20, ColorHolder(222, 0, 0)),
    PLACE(20, 20, ColorHolder(35, 188, 254)),
    PENDING_BREAK(100, 100, ColorHolder(0, 0, 0)),
    PENDING_PLACE(100, 100, ColorHolder(0, 0, 0)),
    DONE(69420, 0x22, ColorHolder(50, 50, 50))
}